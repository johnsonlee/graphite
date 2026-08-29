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

private const val PROPERTY_ID = "id"
private const val PROPERTY_TYPE = "type"
private const val PROPERTY_VALUE = "value"
private const val PROPERTY_NAME = "name"
private const val PROPERTY_CLASS = "class"

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
class CypherExecutor internal constructor(
    private val pipeline: QueryPipeline,
    private val workTrackerFactory: () -> CypherWorkTracker?
) {

    internal constructor(pipeline: QueryPipeline) : this(pipeline, { null })

    constructor(graph: Graph) : this(QueryPipeline(graph), { null })

    constructor(graph: Graph, executionBudget: CypherExecutionBudget) :
        this(QueryPipeline(graph, workTrackingEnabled = true), { CypherWorkTracker(executionBudget) })

    constructor(graph: Graph, executionContext: CypherExecutionContext) :
        this(QueryPipeline(graph, workTrackingEnabled = true), { executionContext.workTracker })

    fun execute(cypher: String): CypherResult {
        // 1. Parse Cypher text into internal AST clauses
        val clauses = CypherDslAdapter.parse(cypher)
        return executeClauses(clauses, maxRows = null, newWorkTracker())
    }

    fun execute(cypher: String, maxRows: Int): CypherResult {
        require(maxRows >= 0) { "maxRows must be non-negative" }
        val clauses = CypherDslAdapter.parse(cypher)
        return executeClauses(clauses, maxRows, newWorkTracker())
    }

    private fun newWorkTracker(): CypherWorkTracker? = workTrackerFactory()

    private fun executeClauses(
        clauses: List<CypherClause>,
        maxRows: Int?,
        workTracker: CypherWorkTracker?
    ): CypherResult {
        workTracker?.checkCancelled()
        // Handle UNION by splitting into sub-queries
        val unionIndex = clauses.indexOfFirst { it is CypherClause.Union }
        if (unionIndex >= 0) {
            return executeUnion(clauses, maxRows, workTracker)
        }

        // Execute via pipeline
        val boundedClauses = maxRows?.let { applyMaxRows(clauses, it) } ?: clauses
        val raw = pipeline.execute(boundedClauses, workTracker)

        // Post-process: convert Node values to property maps
        return materializeResult(raw, workTracker)
    }

    private fun applyMaxRows(clauses: List<CypherClause>, maxRows: Int): List<CypherClause> {
        val bounded = mutableListOf<CypherClause>()
        val segment = mutableListOf<CypherClause>()

        fun flushSegment() {
            bounded.addAll(applyMaxRowsToSegment(segment, maxRows))
            segment.clear()
        }

        clauses.forEach { clause ->
            if (clause is CypherClause.Union) {
                flushSegment()
                bounded.add(clause)
            } else {
                segment.add(clause)
            }
        }
        flushSegment()
        return bounded
    }

    private fun applyMaxRowsToSegment(segment: List<CypherClause>, maxRows: Int): List<CypherClause> {
        val limitIndex = segment.indexOfLast { it is CypherClause.Limit }
        return if (limitIndex < 0) {
            segment + CypherClause.Limit(CypherExpr.Literal(maxRows))
        } else {
            val limit = segment[limitIndex] as CypherClause.Limit
            val literalLimit = literalLimitCount(limit.count)
            when {
                literalLimit == null -> segment.toMutableList().apply {
                    add(limitIndex, CypherClause.Limit(CypherExpr.Literal(maxRows)))
                }
                literalLimit > maxRows -> segment.toMutableList().apply {
                    this[limitIndex] = CypherClause.Limit(CypherExpr.Literal(maxRows))
                }
                else -> segment
            }
        }
    }

    private fun literalLimitCount(expr: CypherExpr): Int? {
        val literal = expr as? CypherExpr.Literal ?: return null
        return when (val value = literal.value) {
            is Number -> value.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            is String -> value.toLongOrNull()
                ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                ?.toInt()
            else -> null
        }
    }

    /**
     * Execute a UNION query by splitting into sub-queries, executing each,
     * and combining results.
     */
    private fun executeUnion(
        clauses: List<CypherClause>,
        maxRows: Int?,
        workTracker: CypherWorkTracker?
    ): CypherResult {
        val segments = mutableListOf<List<CypherClause>>()
        var current = mutableListOf<CypherClause>()
        var unionAll = false

        for ((index, clause) in clauses.withIndex()) {
            if ((index and CANCELLATION_POLL_MASK) == 0) workTracker?.checkCancelled()
            if (clause is CypherClause.Union) {
                segments.add(current)
                current = mutableListOf()
                unionAll = clause.all
            } else {
                current.add(clause)
            }
        }
        segments.add(current)

        return if (unionAll) {
            executeUnionAll(segments, maxRows, workTracker)
        } else {
            executeUnionDistinct(segments, maxRows, workTracker)
        }
    }

    private fun executeUnionAll(
        segments: List<List<CypherClause>>,
        maxRows: Int?,
        workTracker: CypherWorkTracker?
    ): CypherResult {
        var columns = emptyList<String>()
        val rows = mutableListOf<Map<String, Any?>>()
        for ((segmentIndex, segment) in segments.withIndex()) {
            if ((segmentIndex and CANCELLATION_POLL_MASK) == 0) workTracker?.checkCancelled()
            val remaining = maxRows?.minus(rows.size)
            if (remaining != null && remaining <= 0) {
                if (columns.isEmpty()) {
                    columns = pipeline.execute(applyMaxRowsToSegment(segment, 0), workTracker).columns
                }
                break
            }

            val boundedSegment = remaining?.let { applyMaxRowsToSegment(segment, it) } ?: segment
            val result = pipeline.execute(boundedSegment, workTracker)
            if (columns.isEmpty()) columns = result.columns
            if (remaining == null) {
                rows.addAll(result.rows)
            } else {
                rows.addAll(result.rows.take(remaining))
            }
        }
        return materializeResult(CypherResult(columns, rows), workTracker)
    }

    private fun executeUnionDistinct(
        segments: List<List<CypherClause>>,
        maxRows: Int?,
        workTracker: CypherWorkTracker?
    ): CypherResult {
        if (maxRows == 0) {
            val columns = segments.firstOrNull()
                ?.let { pipeline.execute(applyMaxRowsToSegment(it, 0), workTracker).columns }
                .orEmpty()
            return CypherResult(columns, emptyList())
        }
        var columns = emptyList<String>()
        val rows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        // Later segments can still add provenance to a retained duplicate row.
        for ((segmentIndex, segment) in segments.withIndex()) {
            if ((segmentIndex and CANCELLATION_POLL_MASK) == 0) workTracker?.checkCancelled()
            val boundedSegment = maxRows?.let { applyMaxRowsToSegment(segment, it) } ?: segment
            val result = pipeline.execute(boundedSegment, workTracker)
            if (columns.isEmpty()) columns = result.columns
            result.rows.forEachIndexed { index, row ->
                if ((index and CANCELLATION_POLL_MASK) == 0) workTracker?.checkCancelled()
                addDistinctRow(rows, row, maxRows)
            }
        }
        return materializeResult(CypherResult(columns, rows.values.toList()), workTracker)
    }

    /**
     * Convert raw pipeline results into the public format.
     *
     * When a result value is a [GraphiteNode], convert it to a property map
     * so that callers receive serializable data rather than internal objects.
     */
    private fun materializeResult(raw: CypherResult, workTracker: CypherWorkTracker?): CypherResult {
        if (workTracker == null) return materializeResult(raw)
        val rows = raw.rows.mapIndexed { index, row ->
            if ((index and CANCELLATION_POLL_MASK) == 0) workTracker.checkCancelled()
            buildMap {
                row.forEach { (key, value) ->
                    if (key != INTERNAL_PROVENANCE_KEY) put(key, materializeValue(value, workTracker))
                }
                @Suppress("UNCHECKED_CAST")
                val graphIds = row[INTERNAL_PROVENANCE_KEY] as? Set<String>
                if (!graphIds.isNullOrEmpty()) {
                    put(RESULT_METADATA_KEY, mapOf(RESULT_GRAPH_IDS_KEY to graphIds.sorted()))
                }
            }
        }
        workTracker.checkCancelled()
        return CypherResult(raw.columns, rows)
    }

    private fun materializeResult(raw: CypherResult): CypherResult = CypherResult(
        raw.columns,
        raw.rows.map { row ->
            buildMap {
                row.forEach { (key, value) ->
                    if (key != INTERNAL_PROVENANCE_KEY) put(key, materializeValue(value))
                }
                @Suppress("UNCHECKED_CAST")
                val graphIds = row[INTERNAL_PROVENANCE_KEY] as? Set<String>
                if (!graphIds.isNullOrEmpty()) {
                    put(RESULT_METADATA_KEY, mapOf(RESULT_GRAPH_IDS_KEY to graphIds.sorted()))
                }
            }
        }
    )

    @Suppress("UNCHECKED_CAST")
    private fun addDistinctRow(
        rows: MutableMap<Map<String, Any?>, MutableMap<String, Any?>>,
        row: Map<String, Any?>,
        maxRows: Int?
    ) {
        val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
        val existing = rows[visible]
        if (existing != null) {
            val graphIds = (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
            if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
        } else if (maxRows == null || rows.size < maxRows) {
            rows[visible] = row.toMutableMap()
        }
    }

    private fun materializeValue(value: Any?, workTracker: CypherWorkTracker?): Any? = when (value) {
        is GraphiteNode -> nodeToMap(value)
        is QualifiedNode -> nodeToMap(value.node) + mapOf(
            GRAPH_ID_PROPERTY to value.graphId,
            ELEMENT_ID_PROPERTY to value.elementId,
            QUALIFIED_ID_PROPERTY to value.elementId
        )
        is QualifiedEdge -> edgeToMap(value)
        is QualifiedPath -> mapOf(
            GRAPH_ID_PROPERTY to value.graphId,
            "length" to value.edges.size,
            "nodes" to value.nodes.mapIndexed { index, node ->
                if ((index and CANCELLATION_POLL_MASK) == 0) workTracker?.checkCancelled()
                materializeValue(node, workTracker)
            },
            "relationships" to value.edges.mapIndexed { index, edge ->
                if ((index and CANCELLATION_POLL_MASK) == 0) workTracker?.checkCancelled()
                materializeValue(edge, workTracker)
            }
        )
        is List<*> -> value.mapIndexed { index, item ->
            if ((index and CANCELLATION_POLL_MASK) == 0) workTracker?.checkCancelled()
            materializeValue(item, workTracker)
        }
        is Map<*, *> -> buildMap {
            value.entries.forEachIndexed { index, entry ->
                if ((index and CANCELLATION_POLL_MASK) == 0) workTracker?.checkCancelled()
                put(entry.key, materializeValue(entry.value, workTracker))
            }
        }
        else -> value
    }

    private fun materializeValue(value: Any?): Any? = when (value) {
        is GraphiteNode -> nodeToMap(value)
        is QualifiedNode -> nodeToMap(value.node) + mapOf(
            GRAPH_ID_PROPERTY to value.graphId,
            ELEMENT_ID_PROPERTY to value.elementId,
            QUALIFIED_ID_PROPERTY to value.elementId
        )
        is QualifiedEdge -> edgeToMap(value)
        is QualifiedPath -> mapOf(
            GRAPH_ID_PROPERTY to value.graphId,
            "length" to value.edges.size,
            "nodes" to value.nodes.map { materializeValue(it) },
            "relationships" to value.edges.map { materializeValue(it) }
        )
        is List<*> -> value.map { materializeValue(it) }
        is Map<*, *> -> value.mapValues { materializeValue(it.value) }
        else -> value
    }

    private fun edgeToMap(value: QualifiedEdge): Map<String, Any?> = buildMap {
        put(GRAPH_ID_PROPERTY, value.graphId)
        put("from", value.edge.from.value)
        put("to", value.edge.to.value)
        put("fromElementId", "${value.graphId}:${value.edge.from.value}")
        put("toElementId", "${value.graphId}:${value.edge.to.value}")
        put("type", CypherFunctions.type(value.edge))
        when (val edge = value.edge) {
            is io.johnsonlee.graphite.core.DataFlowEdge -> put("kind", edge.kind.name)
            is io.johnsonlee.graphite.core.CallEdge -> {
                put("virtual", edge.isVirtual)
                put("dynamic", edge.isDynamic)
            }
            is io.johnsonlee.graphite.core.TypeEdge -> put("kind", edge.kind.name)
            is io.johnsonlee.graphite.core.ControlFlowEdge -> put("kind", edge.kind.name)
            is io.johnsonlee.graphite.core.ResourceEdge -> put("kind", edge.kind.name)
        }
    }

    private fun nodeToMap(node: GraphiteNode): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            PROPERTY_ID to node.id.value,
            PROPERTY_TYPE to NodePropertyAccessor.nodeTypeName(node)
        )
        when (node) {
            is CallSiteNode -> {
                map["callee_class"] = node.callee.declaringClass.className
                map["callee_name"] = node.callee.name
                map["caller_class"] = node.caller.declaringClass.className
                map["caller_name"] = node.caller.name
                map["line"] = node.lineNumber
            }
            is IntConstant -> map[PROPERTY_VALUE] = node.value
            is StringConstant -> map[PROPERTY_VALUE] = node.value
            is LongConstant -> map[PROPERTY_VALUE] = node.value
            is FloatConstant -> map[PROPERTY_VALUE] = node.value
            is DoubleConstant -> map[PROPERTY_VALUE] = node.value
            is BooleanConstant -> map[PROPERTY_VALUE] = node.value
            is NullConstant -> map[PROPERTY_VALUE] = null
            is EnumConstant -> {
                map["enum_type"] = node.enumType.className
                map[PROPERTY_NAME] = node.enumName
                map[PROPERTY_VALUE] = node.value
            }
            is LocalVariable -> {
                map[PROPERTY_NAME] = node.name
                map[PROPERTY_TYPE] = node.type.className
            }
            is FieldNode -> {
                map[PROPERTY_NAME] = node.descriptor.name
                map[PROPERTY_TYPE] = node.descriptor.type.className
                map[PROPERTY_CLASS] = node.descriptor.declaringClass.className
                map["static"] = node.isStatic
            }
            is ParameterNode -> {
                map["index"] = node.index
                map[PROPERTY_TYPE] = node.type.className
                map["method"] = node.method.signature
            }
            is ReturnNode -> {
                map["method"] = node.method.signature
                map[PROPERTY_CLASS] = node.method.declaringClass.className
                map[PROPERTY_NAME] = node.method.name
                map["parameter_types"] = node.method.parameterTypes.map { it.className }
                map["return_type"] = node.method.returnType.className
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
                map[PROPERTY_VALUE] = node.value
                map["format"] = node.format
                map["profile"] = node.profile
            }
            is AnnotationNode -> {
                map[PROPERTY_NAME] = node.name
                map[PROPERTY_CLASS] = node.className
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
 * Executes one Cypher query over a graph-qualified, read-only union of graphs.
 *
 * Node and edge identity remains local to the owning graph internally and is
 * exposed as `(graphId, id)` / `elementId` in materialized results. Relationship
 * traversal never crosses graph boundaries; independent patterns and joins can
 * bind values from different graphs in the same result row.
 */
class CrossGraphCypherExecutor private constructor(
    graphs: List<CypherGraph>,
    workTrackingEnabled: Boolean,
    workTrackerFactory: () -> CypherWorkTracker?
) {
    private val delegate: CypherExecutor

    constructor(graphs: List<CypherGraph>) : this(graphs, false, { null })

    constructor(graphs: List<CypherGraph>, executionBudget: CypherExecutionBudget) :
        this(graphs, true, { CypherWorkTracker(executionBudget) })

    constructor(graphs: List<CypherGraph>, executionContext: CypherExecutionContext) :
        this(graphs, true, { executionContext.workTracker })

    init {
        require(graphs.map { it.id }.distinct().size == graphs.size) { "Graph ids must be unique" }
        delegate = CypherExecutor(
            QueryPipeline(graphs, workTrackingEnabled),
            workTrackerFactory
        )
    }

    fun execute(cypher: String): CypherResult = delegate.execute(cypher).withExplicitMetadata()

    fun execute(cypher: String, maxRows: Int): CypherResult =
        delegate.execute(cypher, maxRows).withExplicitMetadata()

    private fun CypherResult.withExplicitMetadata(): CypherResult = copy(
        rows = rows.map { row ->
            if (RESULT_METADATA_KEY in row) {
                row
            } else {
                row + (RESULT_METADATA_KEY to mapOf(RESULT_GRAPH_IDS_KEY to emptyList<String>()))
            }
        }
    )
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
