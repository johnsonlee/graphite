package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node

/** Total Cypher ordering shared by row sorting and bounded top-k execution. */
internal object CypherOrdering {
    /**
     * Cypher has a total ordering for ORDER BY that is wider than the set of
     * values accepted by comparison expressions. Null is always greatest, so
     * it is last for ASC and first for DESC. The remaining cross-type order is
     * map, node, relationship, list, path, string, boolean, number.
     */
    fun compare(a: Any?, b: Any?, ascending: Boolean): Int {
        val comparison = when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> compareNonNullOrderValues(a, b)
        }
        return if (ascending) comparison else -comparison
    }

    private fun compareNonNullOrderValues(a: Any, b: Any): Int = when {
        a is Number && b is Number -> compareNumericValues(a, b)
        a is String && b is String -> a.compareTo(b)
        a is Boolean && b is Boolean -> a.compareTo(b)
        else -> compareCompoundValues(a, b)
    }

    private fun compareNumericValues(a: Number, b: Number): Int = when {
        a is Int && b is Int -> a.compareTo(b)
        a is Long && b is Long -> a.compareTo(b)
        a is Double && b is Double -> compareFloatingPoint(a, b)
        a is Float && b is Float -> compareFloatingPoint(a.toDouble(), b.toDouble())
        a is Short && b is Short -> a.compareTo(b)
        a is Byte && b is Byte -> a.compareTo(b)
        else -> compareCypherNumbers(a, b)
    }

    private fun compareCompoundValues(a: Any, b: Any): Int {
        val typeComparison = orderValueType(a).compareTo(orderValueType(b))
        if (typeComparison != 0) return typeComparison
        return when {
            a is Map<*, *> && b is Map<*, *> -> compareMaps(a, b)
            isNodeOrderValue(a) && isNodeOrderValue(b) -> compareNodes(a, b)
            isRelationshipOrderValue(a) && isRelationshipOrderValue(b) -> compareRelationships(a, b)
            a is List<*> && b is List<*> -> compareLists(a, b)
            isPathOrderValue(a) && isPathOrderValue(b) -> compareLists(pathElements(a), pathElements(b))
            else -> a.toString().compareTo(b.toString())
        }
    }

    private fun orderValueType(value: Any): OrderValueType = when {
        value is Map<*, *> -> OrderValueType.MAP
        isNodeOrderValue(value) -> OrderValueType.NODE
        isRelationshipOrderValue(value) -> OrderValueType.RELATIONSHIP
        value is List<*> -> OrderValueType.LIST
        isPathOrderValue(value) -> OrderValueType.PATH
        value is String -> OrderValueType.STRING
        value is Boolean -> OrderValueType.BOOLEAN
        value is Number -> OrderValueType.NUMBER
        else -> OrderValueType.STRING
    }

    private enum class OrderValueType {
        MAP,
        NODE,
        RELATIONSHIP,
        LIST,
        PATH,
        STRING,
        BOOLEAN,
        NUMBER
    }

    private fun compareMaps(a: Map<*, *>, b: Map<*, *>): Int {
        val sizeComparison = a.size.compareTo(b.size)
        if (sizeComparison != 0) return sizeComparison
        val aKeys = a.keys.map { it.toString() }.sorted()
        val bKeys = b.keys.map { it.toString() }.sorted()
        val keyComparison = compareLists(aKeys, bKeys)
        return if (keyComparison != 0) keyComparison else compareMapValues(aKeys, a, b)
    }

    private fun compareMapValues(keys: List<String>, a: Map<*, *>, b: Map<*, *>): Int {
        for (key in keys) {
            val valueComparison = compare(a[key], b[key], ascending = true)
            if (valueComparison != 0) return valueComparison
        }
        return 0
    }

    private fun compareLists(a: List<*>, b: List<*>): Int {
        val commonSize = minOf(a.size, b.size)
        for (index in 0 until commonSize) {
            val comparison = compare(a[index], b[index], ascending = true)
            if (comparison != 0) return comparison
        }
        return a.size.compareTo(b.size)
    }

    private fun compareFloatingPoint(a: Double, b: Double): Int = when {
        a.isNaN() && b.isNaN() -> 0
        a.isNaN() -> 1
        b.isNaN() -> -1
        else -> a.compareTo(b)
    }

    private fun isNodeOrderValue(value: Any): Boolean = value is Node || value is QualifiedNode

    private fun compareNodes(a: Any, b: Any): Int {
        val graphComparison = orderGraphId(a).compareTo(orderGraphId(b))
        if (graphComparison != 0) return graphComparison
        return requireNotNull(nodeValue(a)).id.value.compareTo(requireNotNull(nodeValue(b)).id.value)
    }

    private fun isRelationshipOrderValue(value: Any): Boolean = value is Edge || value is QualifiedEdge

    private fun compareRelationships(a: Any, b: Any): Int {
        val graphComparison = orderGraphId(a).compareTo(orderGraphId(b))
        if (graphComparison != 0) return graphComparison
        val aEdge = requireNotNull(edgeValue(a))
        val bEdge = requireNotNull(edgeValue(b))
        return compareValuesBy(aEdge, bEdge, { it.from.value }, { it.to.value }, { it::class.java.name })
    }

    private fun orderGraphId(value: Any): String = when (value) {
        is QualifiedNode -> value.graphId
        is QualifiedEdge -> value.graphId
        else -> ""
    }

    private fun isPathOrderValue(value: Any): Boolean = value is PathFinder.Path || value is QualifiedPath

    private fun pathElements(value: Any): List<Any> = when (value) {
        is PathFinder.Path -> interleavePath(value.nodes, value.edges)
        is QualifiedPath -> interleavePath(value.nodes, value.edges)
        else -> emptyList()
    }

    private fun interleavePath(nodes: List<*>, edges: List<*>): List<Any> = buildList {
        for (index in nodes.indices) {
            nodes[index]?.let(::add)
            if (index < edges.size) edges[index]?.let(::add)
        }
    }
}
