package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.Node
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

private const val MAX_DYNAMIC_PROPERTY_PLANS = 256
private const val PLAN_CACHE_LOAD_FACTOR = 0.75f
private val methodPropertyNames = Collections.unmodifiableList(listOf(
    METHOD_SIGNATURE_PROPERTY, METHOD_CLASS_PROPERTY, METHOD_NAME_PROPERTY,
    METHOD_PARAMETER_TYPES_PROPERTY, METHOD_RETURN_TYPE_PROPERTY
))
private val qualifiedMethodPropertyNames = Collections.unmodifiableList(methodPropertyNames + GRAPH_ID_PROPERTY)
private val graphPropertyNames = listOf(GRAPH_ID_PROPERTY, ELEMENT_ID_PROPERTY, QUALIFIED_ID_PROPERTY)
private val qualifiedNodePropertyNames = ConcurrentHashMap<Class<out Node>, List<String>>()

/** The same ordered keys as properties(), without materializing static property values. */
internal fun dynamicPropertyKeys(value: Any?): List<String>? = when (value) {
    is MethodValue -> if (value.graphId == null) methodPropertyNames else qualifiedMethodPropertyNames
    is Node -> NodePropertyAccessor.getPropertyNames(value)
    is QualifiedNode -> {
        val node = value.node
        if (node is AnnotationNode) {
            // Annotation values can override metadata keys without changing their position.
            (NodePropertyAccessor.getPropertyNames(node) + graphPropertyNames).distinct()
        } else {
            qualifiedNodePropertyNames[node.javaClass] ?: qualifiedNodePropertyNames.computeIfAbsent(node.javaClass) {
                Collections.unmodifiableList(NodePropertyAccessor.getPropertyNames(node) + graphPropertyNames)
            }
        }
    }
    else -> null
}

internal class DynamicPropertyContainsPlan private constructor(
    val nodeVariable: String,
    val keyVariable: String,
    val term: CypherExpr
) {
    fun evaluate(
        target: Any,
        expected: Any?,
        resolveProperty: (Any?, String) -> Any?,
        checkCancelled: (() -> Unit)?
    ): Boolean? {
        val names = requireNotNull(dynamicPropertyKeys(target))
        val text = expected as? String
        var matched = false
        var unknown = false
        checkCancelled?.invoke()
        for (name in names) {
            checkCancelled?.invoke()
            // Deliberately read the accessor rather than properties()[name]: annotation
            // overrides and global properties can make those values differ.
            val value = resolveProperty(target, name)?.toString()
            if (value == null || text == null) unknown = true else if (value.contains(text)) matched = true
        }
        checkCancelled?.invoke()
        return when {
            matched -> true
            unknown -> null
            else -> false
        }
    }

    companion object {
        @Suppress("ReturnCount", "ComplexCondition", "CyclomaticComplexMethod")
        fun compile(expression: CypherExpr.PredicateFunction): DynamicPropertyContainsPlan? {
            if (!expression.name.equals("any", ignoreCase = true)) return null
            val keys = expression.listExpr as? CypherExpr.FunctionCall ?: return null
            if (keys.distinct || !keys.name.equals("keys", ignoreCase = true)) return null
            val node = keys.args.singleOrNull() as? CypherExpr.Variable ?: return null
            if (node.name == expression.variable) return null
            val predicate = expression.predicate as? CypherExpr.StringOp ?: return null
            if (predicate.op != "CONTAINS" ||
                (predicate.right !is CypherExpr.Literal && predicate.right !is CypherExpr.Parameter)
            ) return null
            val conversion = predicate.left as? CypherExpr.FunctionCall ?: return null
            if (conversion.distinct || !conversion.name.equals("toString", ignoreCase = true)) return null
            val subscript = conversion.args.singleOrNull() as? CypherExpr.Subscript ?: return null
            if (subscript.expression != node || subscript.index != CypherExpr.Variable(expression.variable)) return null
            return DynamicPropertyContainsPlan(node.name, expression.variable, predicate.right)
        }
    }
}

/** Cache unsupported shapes too, so an ordinary predicate is not recompiled for every row. */
internal class DynamicPropertyContainsCache {
    private val plans = object : LinkedHashMap<CypherExpr.PredicateFunction, DynamicPropertyContainsPlan?>(
        MAX_DYNAMIC_PROPERTY_PLANS, PLAN_CACHE_LOAD_FACTOR, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CypherExpr.PredicateFunction, DynamicPropertyContainsPlan?>?
        ): Boolean = size > MAX_DYNAMIC_PROPERTY_PLANS
    }

    fun get(expression: CypherExpr.PredicateFunction): DynamicPropertyContainsPlan? = synchronized(plans) {
        if (!plans.containsKey(expression)) plans[expression] = DynamicPropertyContainsPlan.compile(expression)
        plans[expression]
    }
}
