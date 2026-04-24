package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.Node as GraphiteNode
import io.johnsonlee.graphite.graph.Graph

/**
 * Executes Cypher queries against a Graphite [Graph].
 *
 * Supports the full openCypher read grammar:
 * - `MATCH` / `OPTIONAL MATCH` with arbitrary patterns
 * - `WHERE` with all operators and functions
 * - `RETURN` / `WITH` with aggregation, `DISTINCT`, aliases
 * - `UNWIND` for list expansion
 * - `UNION` / `UNION ALL`
 * - `ORDER BY` / `SKIP` / `LIMIT`
 * - `CASE` expressions
 * - List comprehension
 * - Variable-length paths
 *
 * Query text is parsed by [CypherDslAdapter] into an internal AST
 * ([CypherClause] + [CypherExpr] + [CypherPattern]) and executed by
 * [QueryPipeline] against the graph. Node values in result rows are
 * converted to property maps for interoperability.
 */
class CypherExecutor(private val graph: Graph) {

    private val pipeline = QueryPipeline(graph)

    fun execute(cypher: String): CypherResult {
        // 1. Parse Cypher text into internal AST clauses
        val clauses = CypherDslAdapter.parse(cypher)

        // 2. Handle UNION by splitting into sub-queries
        val unionIndex = clauses.indexOfFirst { it is CypherClause.Union }
        if (unionIndex >= 0) {
            return executeUnion(clauses, cypher)
        }

        // 3. Execute via pipeline
        val raw = pipeline.execute(clauses)

        // 4. Post-process: convert Node values to property maps
        return materializeResult(raw)
    }

    /**
     * Execute a UNION query by splitting into sub-queries, executing each,
     * and combining results.
     */
    private fun executeUnion(clauses: List<CypherClause>, cypher: String): CypherResult {
        val segments = mutableListOf<List<CypherClause>>()
        var current = mutableListOf<CypherClause>()
        var unionAll = false

        for (clause in clauses) {
            if (clause is CypherClause.Union) {
                segments.add(current)
                current = mutableListOf()
                unionAll = clause.all
            } else {
                current.add(clause)
            }
        }
        segments.add(current)

        val results = segments.map { segment ->
            materializeResult(pipeline.execute(segment))
        }

        if (results.isEmpty()) return CypherResult(emptyList(), emptyList())

        val columns = results.first().columns
        val combinedRows = results.flatMap { it.rows }
        val finalRows = if (unionAll) combinedRows else combinedRows.distinct()

        return CypherResult(columns, finalRows)
    }

    /**
     * Convert raw pipeline results into the public format.
     *
     * When a result value is a [GraphiteNode], convert it to a property map
     * so that callers receive serializable data rather than internal objects.
     */
    private fun materializeResult(raw: CypherResult): CypherResult {
        val rows = raw.rows.map { row ->
            row.mapValues { (_, value) -> materializeValue(value) }
        }
        return CypherResult(raw.columns, rows)
    }

    private fun materializeValue(value: Any?): Any? = when (value) {
        is GraphiteNode -> nodeToMap(value)
        is List<*> -> value.map { materializeValue(it) }
        is Map<*, *> -> value.mapValues { materializeValue(it.value) }
        else -> value
    }

    private fun nodeToMap(node: GraphiteNode): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "id" to node.id.value,
            "type" to NodePropertyAccessor.nodeTypeName(node)
        )
        when (node) {
            is CallSiteNode -> {
                map["callee_class"] = node.callee.declaringClass.className
                map["callee_name"] = node.callee.name
                map["caller_class"] = node.caller.declaringClass.className
                map["caller_name"] = node.caller.name
                map["line"] = node.lineNumber
            }
            is IntConstant -> map["value"] = node.value
            is StringConstant -> map["value"] = node.value
            is LongConstant -> map["value"] = node.value
            is FloatConstant -> map["value"] = node.value
            is DoubleConstant -> map["value"] = node.value
            is BooleanConstant -> map["value"] = node.value
            is NullConstant -> map["value"] = null
            is EnumConstant -> {
                map["enum_type"] = node.enumType.className
                map["name"] = node.enumName
                map["value"] = node.value
            }
            is LocalVariable -> {
                map["name"] = node.name
                map["type"] = node.type.className
            }
            is FieldNode -> {
                map["name"] = node.descriptor.name
                map["type"] = node.descriptor.type.className
                map["class"] = node.descriptor.declaringClass.className
                map["static"] = node.isStatic
            }
            is ParameterNode -> {
                map["index"] = node.index
                map["type"] = node.type.className
                map["method"] = node.method.signature
            }
            is ReturnNode -> {
                map["method"] = node.method.signature
                map["actual_type"] = node.actualType?.className
            }
            is ResourceFileNode -> {
                map["path"] = node.path
                map["source"] = node.source
                map["format"] = node.format
                map["profile"] = node.profile
            }
            is ResourceValueNode -> {
                map["path"] = node.path
                map["key"] = node.key
                map["value"] = node.value
                map["format"] = node.format
                map["profile"] = node.profile
            }
            is AnnotationNode -> {
                map["name"] = node.name
                map["class"] = node.className
                map["member"] = node.memberName
                for ((k, v) in node.values) {
                    map[k] = v
                }
            }
        }
        return map
    }
}

/**
 * A property filter extracted from a WHERE clause.
 */
data class PropertyFilter(
    val property: String,
    val operator: FilterOperator,
    val value: Any?,
    val ownerLabels: Set<String>,
    val variable: String
) {
    fun matches(node: GraphiteNode): Boolean {
        val actual = NodePropertyAccessor.getProperty(node, property)
        return when (operator) {
            FilterOperator.EQUALS -> compareEquals(actual, value)
            FilterOperator.NOT_EQUALS -> !compareEquals(actual, value)
            FilterOperator.LESS_THAN -> compareNumeric(actual, value) { a, b -> a < b }
            FilterOperator.GREATER_THAN -> compareNumeric(actual, value) { a, b -> a > b }
            FilterOperator.LESS_THAN_OR_EQUAL -> compareNumeric(actual, value) { a, b -> a <= b }
            FilterOperator.GREATER_THAN_OR_EQUAL -> compareNumeric(actual, value) { a, b -> a >= b }
            FilterOperator.REGEX -> {
                val pattern = value?.toString() ?: return false
                val actualStr = actual?.toString() ?: return false
                pattern.toRegex().matches(actualStr)
            }
            FilterOperator.STARTS_WITH -> {
                val prefix = value?.toString() ?: return false
                actual?.toString()?.startsWith(prefix) == true
            }
            FilterOperator.ENDS_WITH -> {
                val suffix = value?.toString() ?: return false
                actual?.toString()?.endsWith(suffix) == true
            }
            FilterOperator.CONTAINS -> {
                val substring = value?.toString() ?: return false
                actual?.toString()?.contains(substring) == true
            }
        }
    }

    private fun compareEquals(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        // Handle numeric type coercion
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a == b || a.toString() == b.toString()
    }

    private fun compareNumeric(a: Any?, b: Any?, op: (Double, Double) -> Boolean): Boolean {
        val aNum = (a as? Number)?.toDouble() ?: return false
        val bNum = (b as? Number)?.toDouble() ?: return false
        return op(aNum, bNum)
    }
}

enum class FilterOperator {
    EQUALS, NOT_EQUALS,
    LESS_THAN, GREATER_THAN,
    LESS_THAN_OR_EQUAL, GREATER_THAN_OR_EQUAL,
    REGEX, STARTS_WITH, ENDS_WITH, CONTAINS
}
