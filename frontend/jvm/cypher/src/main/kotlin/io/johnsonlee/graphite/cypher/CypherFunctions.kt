package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant

internal const val FUNCTION_COUNT = "count"
internal const val FUNCTION_SUM = "sum"
internal const val FUNCTION_AVG = "avg"
internal const val FUNCTION_MIN = "min"
internal const val FUNCTION_MAX = "max"
internal const val FUNCTION_COLLECT = "collect"
internal const val FUNCTION_PERCENTILE_CONT = "percentilecont"
internal const val FUNCTION_PERCENTILE_DISC = "percentiledisc"
internal const val FUNCTION_STDEV = "stdev"
internal const val FUNCTION_STDEVP = "stdevp"
internal const val DEFAULT_PERCENTILE = 0.5
internal const val CONSTANT_LABEL = "Constant"

internal data class NodeLabelDescriptor(val type: Class<out Node>, val labels: List<String>)

internal val nodeLabelDescriptors =
    listOf(
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
        NodeLabelDescriptor(AnnotationNode::class.java, listOf("AnnotationNode", "Annotation")))

/**
 * Complete openCypher function library.
 *
 * All scalar functions accept [Any?] arguments and return [Any?]. Aggregation functions are dispatched separately via [aggregate].
 */
object CypherFunctions {

    internal fun call(name: String, args: List<Any?>, checkCancelled: (() -> Unit)?, graph: io.johnsonlee.graphite.graph.Graph?): Any? =
        if (name.equals("properties", ignoreCase = true) && args.firstOrNull() is Node) {
            NodePropertyAccessor.getAllProperties(args[0] as Node, graph)
        } else if (name.equals("keys", ignoreCase = true)) {
            dynamicPropertyKeys(args[0], graph)
        } else {
            dispatch(name, args, checkCancelled)
        }

    fun call(name: String, args: List<Any?>): Any? = dispatch(name, args, null)

    internal fun call(name: String, args: List<Any?>, checkCancelled: () -> Unit): Any? = dispatch(name, args, checkCancelled)

    private fun dispatch(name: String, args: List<Any?>, checkCancelled: (() -> Unit)?): Any? {
        val normalized = name.lowercase()
        if (normalized in AGGREGATION_NAMES) throw CypherAggregationException(name)
        val implementation = scalarFunctions[normalized] ?: throw CypherException("Unknown function: $name")
        return implementation(args, checkCancelled)
    }

    // ========================================================================
    // Aggregation functions (called during result aggregation, not inline)
    // ========================================================================

    fun aggregate(name: String, values: List<Any?>): Any? =
        when (name.lowercase()) {
            FUNCTION_COUNT -> values.count { it != null }.toLong()
            FUNCTION_SUM -> values.filterNotNull().sumOf { numericValue(it) }
            FUNCTION_AVG ->
                values.filterNotNull().let { present -> if (present.isEmpty()) null else present.sumOf { numericValue(it) } / present.size }
            FUNCTION_MIN -> values.filterNotNull().minByOrNull { numericValue(it) }
            FUNCTION_MAX -> values.filterNotNull().maxByOrNull { numericValue(it) }
            FUNCTION_COLLECT -> values.filterNotNull()
            FUNCTION_PERCENTILE_CONT -> percentileCont(values, DEFAULT_PERCENTILE)
            FUNCTION_PERCENTILE_DISC -> percentileDisc(values, DEFAULT_PERCENTILE)
            FUNCTION_STDEV -> stdev(values, sample = true)
            FUNCTION_STDEVP -> stdev(values, sample = false)
            else -> throw CypherException("Unknown aggregation: $name")
        }

    internal fun aggregate(name: String, values: List<Any?>, checkCancelled: () -> Unit): Any? =
        when (name.lowercase()) {
            FUNCTION_COUNT -> countValues(values, checkCancelled)
            FUNCTION_SUM -> sumValues(values, checkCancelled)
            FUNCTION_AVG -> averageValues(values, checkCancelled)
            FUNCTION_MIN -> extremeValue(values, checkCancelled, minimum = true)
            FUNCTION_MAX -> extremeValue(values, checkCancelled, minimum = false)
            FUNCTION_COLLECT -> collectValues(values, checkCancelled)
            FUNCTION_PERCENTILE_CONT -> percentileCont(values, DEFAULT_PERCENTILE, checkCancelled)
            FUNCTION_PERCENTILE_DISC -> percentileDisc(values, DEFAULT_PERCENTILE, checkCancelled)
            FUNCTION_STDEV -> stdev(values, sample = true, checkCancelled)
            FUNCTION_STDEVP -> stdev(values, sample = false, checkCancelled)
            else -> throw CypherException("Unknown aggregation: $name")
        }

    /** Aggregation with an explicit percentile parameter. */
    fun aggregate(name: String, values: List<Any?>, percentile: Double): Any? =
        when (name.lowercase()) {
            FUNCTION_PERCENTILE_CONT -> percentileCont(values, percentile)
            FUNCTION_PERCENTILE_DISC -> percentileDisc(values, percentile)
            else -> aggregate(name, values)
        }

    fun isAggregation(name: String): Boolean = name.lowercase() in AGGREGATION_NAMES

    // ========================================================================
    // Implementation details
    // ========================================================================

    private val AGGREGATION_NAMES =
        setOf(
            FUNCTION_COUNT,
            FUNCTION_SUM,
            FUNCTION_AVG,
            FUNCTION_MIN,
            FUNCTION_MAX,
            FUNCTION_COLLECT,
            FUNCTION_PERCENTILE_CONT,
            FUNCTION_PERCENTILE_DISC,
            FUNCTION_STDEV,
            FUNCTION_STDEVP)

    internal fun toInteger(value: Any?): Long? = integerValue(value)

    internal fun toFloat(value: Any?): Double? = floatValue(value)

    internal fun toBoolean(value: Any?): Boolean? = booleanValue(value)

    internal fun labels(value: Any?): List<String> = entityLabels(value)

    internal fun type(value: Any?): String? = relationshipType(value)

    internal fun toDouble(value: Any?): Double = numericValue(value)
}

/** A materialized, null-free list of integral values produced by Cypher's range() function. */
internal class CypherRangeValues : ArrayList<Long>()

class CypherException(message: String) : RuntimeException(message)

class CypherAggregationException(val functionName: String) :
    RuntimeException("Aggregation function '$functionName' must be used in RETURN or WITH clause")
