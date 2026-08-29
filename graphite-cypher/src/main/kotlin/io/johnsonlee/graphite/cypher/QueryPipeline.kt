package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookupStrategy
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringProperty
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import io.johnsonlee.graphite.graph.nodesByTransformedStringProperty
import java.util.PriorityQueue
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

private const val COUNT_QUERY_CLAUSES = 2
private const val LABEL_HISTOGRAM_QUERY_CLAUSES = 5
private const val DISTINCT_LIMIT_QUERY_CLAUSES = 3
private const val FILTERED_LIMIT_QUERY_CLAUSES = 4
private const val SINGLE_HOP_LIMIT_QUERY_CLAUSES = 3
private const val SINGLE_HOP_PATTERN_ELEMENTS = 3
private const val SINGLE_GRAPH_ID = "single"
private const val MAX_ORDERED_PROPERTY_TOP_K = 10_000
private const val DIRECT_STRING_PARALLELISM_PROPERTY = "graphite.cypher.directStringParallelism"
private const val DEFAULT_DIRECT_STRING_PARALLELISM = 2

private val directStringWorkerNumber = AtomicInteger()
private val directStringWorkerActive = ThreadLocal.withInitial { false }
private val directStringGraphReferenceQueue = ReferenceQueue<Graph>()
private val directStringGraphLocks = mutableMapOf<IdentityWeakGraphReference, ReentrantLock>()

internal fun directStringGraphLock(graph: Graph): ReentrantLock = synchronized(directStringGraphLocks) {
    while (true) {
        val expired = directStringGraphReferenceQueue.poll() as? IdentityWeakGraphReference ?: break
        directStringGraphLocks.remove(expired)
    }
    directStringGraphLocks[IdentityWeakGraphReference(graph)] ?: ReentrantLock().also { lock ->
        directStringGraphLocks[IdentityWeakGraphReference(graph, directStringGraphReferenceQueue)] = lock
    }
}

private class IdentityWeakGraphReference(
    graph: Graph,
    queue: ReferenceQueue<Graph>? = null
) : WeakReference<Graph>(graph, queue) {
    private val identityHash = System.identityHashCode(graph)

    override fun hashCode(): Int = identityHash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val graph = get() ?: return false
        return other is IdentityWeakGraphReference && graph === other.get()
    }
}

private val directStringParallelism: Int by lazy {
    val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    System.getProperty(DIRECT_STRING_PARALLELISM_PROPERTY)
        ?.toIntOrNull()
        ?.coerceIn(1, processors)
        ?: minOf(DEFAULT_DIRECT_STRING_PARALLELISM, processors)
}
private val directStringExecutor by lazy {
    Executors.newFixedThreadPool(directStringParallelism) { runnable ->
        Thread(runnable, "graphite-cypher-scan-${directStringWorkerNumber.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}

private class WorkTrackingSequence<T>(
    private val source: Sequence<T>,
    private val workTracker: CypherWorkTracker
) : Sequence<T> {
    override fun iterator(): Iterator<T> {
        val sourceIterator = source.iterator()
        return object : Iterator<T> {
            override fun hasNext(): Boolean = sourceIterator.hasNext()

            override fun next(): T {
                if (!sourceIterator.hasNext()) throw NoSuchElementException()
                workTracker.consume()
                return sourceIterator.next()
            }
        }
    }
}

private val QUALIFIED_NODE_PROPERTIES = setOf(GRAPH_ID_PROPERTY, ELEMENT_ID_PROPERTY, QUALIFIED_ID_PROPERTY)
private val DIRECT_STRING_NODE_PROPERTIES = listOf(
    EnumConstant::class.java to setOf("name"),
    LocalVariable::class.java to setOf("name"),
    FieldNode::class.java to setOf("class", "name"),
    CallSiteNode::class.java to setOf("caller_class", "caller_name", "callee_class", "callee_name"),
    AnnotationNode::class.java to setOf(
        "class",
        "name",
        "caller_class",
        "caller_name",
        "callee_class",
        "callee_name"
    )
)

/**
 * Executes a sequence of [CypherClause] elements against a [Graph],
 * maintaining a result set (list of binding maps) that flows through each clause.
 *
 * Supported clauses:
 * - `MATCH` / `OPTIONAL MATCH` -- pattern matching against the graph
 * - `WHERE` -- row filtering via boolean expressions
 * - `RETURN` / `WITH` -- projection and aggregation
 * - `UNWIND` -- list expansion
 * - `ORDER BY` / `SKIP` / `LIMIT` -- result ordering and pagination
 * - `UNION` / `UNION ALL` -- result set combination
 *
 * Write clauses (`CREATE`, `DELETE`, `SET`, `REMOVE`) are not executed
 * because Graphite graphs are immutable; they are silently ignored.
 */
@Suppress("LargeClass")
class QueryPipeline private constructor(
    private val sources: List<CypherGraph>,
    private val qualified: Boolean,
    private val workTrackingEnabled: Boolean
) {

    constructor(graph: Graph) : this(listOf(CypherGraph(SINGLE_GRAPH_ID, graph)), false, false)

    internal constructor(graph: Graph, workTrackingEnabled: Boolean) :
        this(listOf(CypherGraph(SINGLE_GRAPH_ID, graph)), false, workTrackingEnabled)

    internal constructor(graphs: List<CypherGraph>) : this(graphs, true, false)

    internal constructor(graphs: List<CypherGraph>, workTrackingEnabled: Boolean) :
        this(graphs, true, workTrackingEnabled)

    init {
        require(sources.map { it.id }.distinct().size == sources.size) { "Graph ids must be unique" }
    }

    private val graph: Graph get() = sources.single().graph

    private val activeWorkTracker = ThreadLocal<CypherWorkTracker?>()
    private val evaluator = if (workTrackingEnabled) ExpressionEvaluator(::checkCancelled) else ExpressionEvaluator()

    /**
     * Execute a list of clauses and return the final result.
     */
    fun execute(clauses: List<CypherClause>): CypherResult = execute(clauses, null)

    internal fun execute(
        clauses: List<CypherClause>,
        workTracker: CypherWorkTracker?
    ): CypherResult {
        if (workTracker == null) return executeWithActiveBudget(clauses)
        val previousTracker = activeWorkTracker.get()
        activeWorkTracker.set(workTracker)
        return try {
            executeWithActiveBudget(clauses)
        } finally {
            if (previousTracker == null) {
                activeWorkTracker.remove()
            } else {
                activeWorkTracker.set(previousTracker)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun executeWithActiveBudget(clauses: List<CypherClause>): CypherResult {
        checkCancelled()
        val fastResult = tryFastNodeCount(clauses)
            ?: tryFastLabelHistogram(clauses)
            ?: tryFastOrderedPropertyLimit(clauses)
            ?: tryFastDistinctPropertyLimit(clauses)
            ?: tryFastFilteredNodeLimit(clauses)
            ?: tryFastSingleHopRelationshipLimit(clauses)
        if (fastResult != null) return fastResult

        var rows: List<Map<String, Any?>> = listOf(emptyMap())
        var columns: List<String> = emptyList()

        // Pre-compute early limit: if clauses between MATCH and LIMIT contain
        // no WHERE, WITH, ORDER BY, UNWIND, or aggregation, we can stop the
        // node scan early instead of materialising all candidates first.
        val earlyLimit = computeEarlyLimit(clauses)

        var consumedWhereIndex = -1
        for (clauseIndex in clauses.indices) {
            checkCancelled()
            val clause = clauses[clauseIndex]
            when (clause) {
                is CypherClause.Match -> {
                    val pushedWhere = clauses.getOrNull(clauseIndex + 1) as? CypherClause.Where
                    val soughtRows = pushedWhere
                        ?.takeIf { it.condition is CypherExpr.Comparison }
                        ?.let { tryElementIdSeek(clause, it, rows) }
                    rows = if (soughtRows != null) {
                        consumedWhereIndex = clauseIndex + 1
                        soughtRows
                    } else if (clause.optional) {
                        executeOptionalMatch(clause.patterns, rows)
                    } else {
                        executeMatch(clause.patterns, rows, earlyLimit)
                    }
                }
                is CypherClause.Where -> {
                    if (clauseIndex != consumedWhereIndex) rows = executeWhere(clause, rows)
                }
                is CypherClause.Return -> {
                    val (newRows, newColumns) = projectAndAggregate(clause.items, rows, clause.distinct)
                    rows = newRows
                    columns = newColumns
                }
                is CypherClause.With -> {
                    val (newRows, newColumns) = projectAndAggregate(clause.items, rows, clause.distinct)
                    rows = newRows
                    columns = newColumns
                    // WITH can have an inline WHERE
                    if (clause.where != null) {
                        rows = executeInlineWhere(clause.where, rows)
                    }
                }
                is CypherClause.Unwind -> rows = executeUnwind(clause, rows)
                is CypherClause.OrderBy -> rows = executeOrderBy(clause, rows)
                is CypherClause.Skip -> {
                    val count = evaluateToInt(clause.count, rows.firstOrNull() ?: emptyMap())
                    rows = rows.drop(count)
                }
                is CypherClause.Limit -> {
                    val count = evaluateToInt(clause.count, rows.firstOrNull() ?: emptyMap())
                    rows = rows.take(count)
                }
                is CypherClause.Union -> TODO("UNION is handled by CypherExecutor, not QueryPipeline")
                is CypherClause.Create -> TODO("CREATE is not supported — graph is immutable")
                is CypherClause.Delete -> TODO("DELETE is not supported — graph is immutable")
                is CypherClause.Set -> TODO("SET is not supported — graph is immutable")
                is CypherClause.Remove -> TODO("REMOVE is not supported — graph is immutable")
            }
            checkCancelled()
        }

        if (columns.isEmpty() && rows.isNotEmpty()) {
            columns = rows.first().keys.toList()
        }

        checkCancelled()
        return CypherResult(columns, rows)
    }

    @Suppress("ReturnCount")
    private fun tryElementIdSeek(
        match: CypherClause.Match,
        where: CypherClause.Where,
        inputRows: List<Map<String, Any?>>
    ): List<Map<String, Any?>>? {
        if (match.optional || match.patterns.size != 1) return null
        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) return null
        val nodePattern = pattern.elements.single() as? PatternElement.NodePattern ?: return null
        val variable = nodePattern.variable ?: return null
        if (inputRows.any { variable in it }) return null

        val elementId = elementIdEquality(where.condition, variable) ?: return null
        val sourceAndNode = seekNode(elementId) ?: return emptyList()
        val (source, node) = sourceAndNode
        val nodeClass = nodePattern.labels.firstOrNull()
            ?.let { NodePropertyAccessor.resolveNodeLabel(it) }
            ?: Node::class.java
        if (!nodeClass.isInstance(node)) return emptyList()

        val candidate = nodeValue(source, node)
        val results = mutableListOf<Map<String, Any?>>()
        for (inputRow in inputRows) {
            if (!matchesNodeConstraints(candidate, nodePattern, inputRow)) continue
            val bindings = inputRow.toMutableMap()
            bindings[variable] = candidate
            addProvenance(bindings, candidate)
            if (evaluator.evaluate(where.condition, bindings) == true) results.add(bindings)
        }
        return results
    }

    @Suppress("ReturnCount")
    private fun elementIdEquality(expression: CypherExpr, variable: String): String? {
        val comparison = expression as? CypherExpr.Comparison ?: return null
        if (comparison.op != "=") return null
        return when {
            isElementIdReference(comparison.left, variable) ->
                (comparison.right as? CypherExpr.Literal)?.value as? String
            isElementIdReference(comparison.right, variable) ->
                (comparison.left as? CypherExpr.Literal)?.value as? String
            else -> null
        }
    }

    private fun isElementIdReference(expression: CypherExpr, variable: String): Boolean = when (expression) {
        is CypherExpr.FunctionCall -> expression.name.equals("elementId", ignoreCase = true) &&
            expression.args.singleOrNull() == CypherExpr.Variable(variable)
        is CypherExpr.Property -> expression.expression == CypherExpr.Variable(variable) &&
            expression.propertyName in setOf(ELEMENT_ID_PROPERTY, QUALIFIED_ID_PROPERTY)
        else -> false
    }

    @Suppress("ReturnCount")
    private fun seekNode(elementId: String): Pair<CypherGraph, Node>? {
        if (!qualified) {
            val nodeId = elementId.toIntOrNull() ?: return null
            return sources.single().let { source -> trackedNode(source.graph, NodeId(nodeId))?.let { source to it } }
        }

        val separator = elementId.lastIndexOf(':')
        if (separator < 0 || separator == elementId.lastIndex) return null
        val graphId = elementId.substring(0, separator)
        val nodeId = elementId.substring(separator + 1).toIntOrNull() ?: return null
        val source = sources.firstOrNull { it.id == graphId } ?: return null
        return trackedNode(source.graph, NodeId(nodeId))?.let { source to it }
    }

    /**
     * Fast path for simple count queries:
     *
     *   MATCH (n:Label) RETURN count(*)
     *   MATCH (n:Label) RETURN count(n)
     *
     * This avoids scanning and materializing every node when the graph backend
     * already has a type index count.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    private fun tryFastNodeCount(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size != COUNT_QUERY_CLAUSES) return null
        val match = clauses[0] as? CypherClause.Match ?: return null
        val ret = clauses[1] as? CypherClause.Return ?: return null
        if (match.optional || ret.distinct || match.patterns.size != 1 || ret.items.size != 1) return null

        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) return null

        val nodePattern = pattern.elements.single() as? PatternElement.NodePattern ?: return null
        if (nodePattern.labels.size > 1 || nodePattern.properties.isNotEmpty()) return null

        val returnItem = ret.items.single()
        val countedVariable = countedVariable(returnItem.expression) ?: return null
        if (countedVariable != "*" && countedVariable != nodePattern.variable) return null

        val nodeClass = nodePattern.labels.firstOrNull()
            ?.let { NodePropertyAccessor.resolveNodeLabel(it) }
            ?: Node::class.java
        var count = 0L
        val provenance = if (qualified) linkedSetOf<String>() else null
        for (source in sources) {
            val sourceCount = source.graph.nodeCount(nodeClass) ?: return null
            count += sourceCount
            if (sourceCount > 0) provenance?.add(source.id)
        }
        val column = returnItem.alias ?: returnItem.expression.toCypherString()
        val row = mutableMapOf<String, Any?>(column to count)
        if (!provenance.isNullOrEmpty()) row[INTERNAL_PROVENANCE_KEY] = provenance
        return CypherResult(
            columns = listOf(column),
            rows = listOf(row)
        )
    }

    private fun countedVariable(expr: CypherExpr): String? = when (expr) {
        is CypherExpr.CountStar -> "*"
        is CypherExpr.FunctionCall -> {
            if (!expr.name.equals("count", ignoreCase = true) || expr.distinct || expr.args.size != 1) {
                null
            } else {
                (expr.args.single() as? CypherExpr.Variable)?.name
            }
        }
        else -> null
    }

    /**
     * Fast path for Graphite schema discovery:
     *
     *   MATCH (n)
     *   UNWIND labels(n) AS label
     *   RETURN label, count(*) AS count
     *   ORDER BY count DESC
     *   LIMIT k
     *
     * Graphite's labels are derived from concrete node types. Backends with a
     * type index can therefore answer this histogram without loading nodes.
     */
    @Suppress("ReturnCount")
    private fun tryFastLabelHistogram(clauses: List<CypherClause>): CypherResult? {
        val query = LabelHistogramQuery.compile(clauses) ?: return null
        if (query.limit <= 0) return CypherResult(query.columns, emptyList())

        val counts = linkedMapOf<String, Long>()
        val provenance = if (qualified) mutableMapOf<String, MutableSet<String>>() else null
        for (source in sources) {
            for (descriptor in nodeLabelDescriptors) {
                val count = source.graph.nodeCount(descriptor.type) ?: return null
                if (count <= 0) continue
                for (label in descriptor.labels) {
                    counts[label] = counts.getOrDefault(label, 0L) + count
                    provenance?.getOrPut(label, ::linkedSetOf)?.add(source.id)
                }
            }
        }

        val comparator = compareBy<Map.Entry<String, Long>> { it.value }
        val ordered = counts.entries.sortedWith(
            if (query.ascending) comparator else comparator.reversed()
        )
        val rows = ordered.take(query.limit).map { (label, count) ->
            mutableMapOf<String, Any?>(
                query.labelColumn to label,
                query.countColumn to count
            ).apply {
                provenance?.get(label)?.takeIf { it.isNotEmpty() }?.let { put(INTERNAL_PROVENANCE_KEY, it) }
            }
        }
        return CypherResult(query.columns, rows)
    }

    private data class LabelHistogramQuery(
        val labelColumn: String,
        val countColumn: String,
        val ascending: Boolean,
        val limit: Int
    ) {
        val columns: List<String> = listOf(labelColumn, countColumn)

        companion object {
            @Suppress("ComplexCondition", "CyclomaticComplexMethod", "MagicNumber", "ReturnCount")
            fun compile(clauses: List<CypherClause>): LabelHistogramQuery? {
                if (clauses.size != LABEL_HISTOGRAM_QUERY_CLAUSES) return null
                val match = clauses[0] as? CypherClause.Match ?: return null
                val unwind = clauses[1] as? CypherClause.Unwind ?: return null
                val ret = clauses[2] as? CypherClause.Return ?: return null
                val orderBy = clauses[3] as? CypherClause.OrderBy ?: return null
                val limit = clauses[4] as? CypherClause.Limit ?: return null
                if (match.optional || match.patterns.size != 1 || ret.distinct || ret.items.size != 2) return null

                val pattern = match.patterns.single()
                if (pattern.pathVariable != null || pattern.elements.size != 1) return null
                val nodePattern = pattern.elements.single() as? PatternElement.NodePattern ?: return null
                val nodeVariable = nodePattern.variable ?: return null
                if (nodePattern.labels.isNotEmpty() || nodePattern.properties.isNotEmpty()) return null

                val labelsCall = unwind.expression as? CypherExpr.FunctionCall ?: return null
                if (!labelsCall.name.equals("labels", ignoreCase = true) || labelsCall.distinct) return null
                if (labelsCall.args.singleOrNull() != CypherExpr.Variable(nodeVariable)) return null

                val labelItem = ret.items[0]
                if (labelItem.expression != CypherExpr.Variable(unwind.variable)) return null
                val countItem = ret.items[1]
                if (countItem.expression != CypherExpr.CountStar) return null
                val labelColumn = labelItem.alias ?: labelItem.expression.toCypherString()
                val countColumn = countItem.alias ?: countItem.expression.toCypherString()

                val sort = orderBy.items.singleOrNull() ?: return null
                if (sort.expression != CypherExpr.Variable(countColumn)) return null
                val limitCount = (limit.count as? CypherExpr.Literal)?.value as? Number ?: return null
                return LabelHistogramQuery(labelColumn, countColumn, sort.ascending, limitCount.toCypherInt())
            }
        }
    }

    /**
     * Keeps only the best k rows for a direct property projection instead of
     * materializing and sorting every matching node.
     */
    @Suppress("ReturnCount")
    private fun tryFastOrderedPropertyLimit(clauses: List<CypherClause>): CypherResult? {
        val query = OrderedPropertyLimitQuery.compile(clauses) ?: return null
        if (query.limit <= 0) return CypherResult(query.columns, emptyList())

        var comparisons = 0
        val comparator = Comparator<RankedProjectedRow> { left, right ->
            if (workTrackingEnabled) pollCancellation(comparisons++)
            for (sort in query.sortItems) {
                val comparison = compareNullable(left.row[sort.column], right.row[sort.column])
                if (comparison != 0) {
                    return@Comparator if (sort.ascending) comparison else -comparison
                }
            }
            left.encounterOrder.compareTo(right.encounterOrder)
        }
        val topRows = PriorityQueue(query.limit, comparator.reversed())
        var encounterOrder = 0L
        for (candidate in nodeCandidates(query.nodeClass)) {
            val row = linkedMapOf<String, Any?>()
            for (projection in query.projections) {
                row[projection.column] = nodeProperty(candidate, projection.property)
            }
            val provenance = provenanceOf(candidate)
            if (provenance.isNotEmpty()) row[INTERNAL_PROVENANCE_KEY] = provenance

            val ranked = RankedProjectedRow(row, encounterOrder++)
            if (topRows.size < query.limit) {
                topRows.add(ranked)
            } else if (comparator.compare(ranked, topRows.peek()) < 0) {
                topRows.poll()
                topRows.add(ranked)
            }
        }
        checkCancelled()
        return CypherResult(
            columns = query.columns,
            rows = topRows.toList().sortedWith(comparator).map(RankedProjectedRow::row)
        )
    }

    private data class RankedProjectedRow(
        val row: MutableMap<String, Any?>,
        val encounterOrder: Long
    )

    private data class OrderedPropertyLimitQuery(
        val nodeClass: Class<out Node>,
        val projections: List<PropertyProjection>,
        val sortItems: List<OrderedColumn>,
        val limit: Int
    ) {
        val columns: List<String> = projections.map(PropertyProjection::column)

        companion object {
            @Suppress("ComplexCondition", "CyclomaticComplexMethod", "MagicNumber", "ReturnCount")
            fun compile(clauses: List<CypherClause>): OrderedPropertyLimitQuery? {
                if (clauses.size != 4) return null
                val match = clauses[0] as? CypherClause.Match ?: return null
                val ret = clauses[1] as? CypherClause.Return ?: return null
                val orderBy = clauses[2] as? CypherClause.OrderBy ?: return null
                val limit = clauses[3] as? CypherClause.Limit ?: return null
                if (match.optional || match.patterns.size != 1 || ret.distinct || ret.items.isEmpty()) return null

                val pattern = match.patterns.single()
                if (pattern.pathVariable != null || pattern.elements.size != 1) return null
                val nodePattern = pattern.elements.single() as? PatternElement.NodePattern ?: return null
                val variable = nodePattern.variable ?: return null
                if (nodePattern.labels.size > 1 || nodePattern.properties.isNotEmpty()) return null

                val projections = ret.items.map { item ->
                    val property = item.expression as? CypherExpr.Property ?: return null
                    val owner = property.expression as? CypherExpr.Variable ?: return null
                    if (owner.name != variable) return null
                    PropertyProjection(property.propertyName, item.alias ?: item.expression.toCypherString())
                }
                val columns = projections.map(PropertyProjection::column)
                if (columns.toSet().size != columns.size) return null
                val sortItems = orderBy.items.map { item ->
                    val column = (item.expression as? CypherExpr.Variable)?.name ?: return null
                    if (column !in columns) return null
                    OrderedColumn(column, item.ascending)
                }
                if (sortItems.isEmpty()) return null
                val limitCount = ((limit.count as? CypherExpr.Literal)?.value as? Number)?.toCypherInt() ?: return null
                if (limitCount > MAX_ORDERED_PROPERTY_TOP_K) return null
                val nodeClass = nodePattern.labels.firstOrNull()
                    ?.let(NodePropertyAccessor::resolveNodeLabel)
                    ?: Node::class.java
                return OrderedPropertyLimitQuery(nodeClass, projections, sortItems, limitCount)
            }
        }
    }

    private data class PropertyProjection(val property: String, val column: String)

    private data class OrderedColumn(val column: String, val ascending: Boolean)

    /**
     * Fast path for:
     *
     *   MATCH (n:Label) RETURN DISTINCT n.property LIMIT k
     *
     * Without ORDER BY, Cypher does not require a globally sorted result. The
     * existing implementation preserves first-seen order via List.distinct(),
     * so we can stop after the first k distinct property values instead of
     * materializing every matching node first.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    private fun tryFastDistinctPropertyLimit(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size != DISTINCT_LIMIT_QUERY_CLAUSES) return null
        val match = clauses[0] as? CypherClause.Match ?: return null
        val ret = clauses[1] as? CypherClause.Return ?: return null
        val limit = clauses[2] as? CypherClause.Limit ?: return null
        if (match.optional || !ret.distinct || match.patterns.size != 1 || ret.items.size != 1) return null

        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) return null

        val nodePattern = pattern.elements.single() as? PatternElement.NodePattern ?: return null
        if (nodePattern.variable == null || nodePattern.labels.size > 1 || nodePattern.properties.isNotEmpty()) return null

        val returnItem = ret.items.single()
        val propertyName = propertyProjection(returnItem.expression, nodePattern.variable) ?: return null
        val limitCount = literalLimitCount(limit.count) ?: return null
        if (limitCount <= 0) {
            val column = returnItem.alias ?: returnItem.expression.toCypherString()
            return CypherResult(listOf(column), emptyList())
        }

        val nodeClass = nodePattern.labels.firstOrNull()
            ?.let { NodePropertyAccessor.resolveNodeLabel(it) }
            ?: Node::class.java
        val column = returnItem.alias ?: returnItem.expression.toCypherString()
        val seen = LinkedHashMap<Any?, Set<String>>()
        for (candidate in nodeCandidates(nodeClass)) {
            val variable = nodePattern.variable
            val value = evaluator.evaluate(
                CypherExpr.Property(CypherExpr.Variable(variable), propertyName),
                mapOf(variable to candidate)
            )
            val provenance = provenanceOf(candidate)
            if (value in seen) {
                seen[value] = seen.getValue(value) + provenance
            } else if (seen.size < limitCount) {
                seen[value] = provenance
            }
            // A qualified query must keep scanning so duplicate values in
            // later graphs contribute to the row's complete provenance.
            if (!qualified && seen.size >= limitCount) break
        }
        return CypherResult(
            columns = listOf(column),
            rows = seen.map { (value, provenance) ->
                mutableMapOf<String, Any?>(column to value).apply {
                    if (provenance.isNotEmpty()) put(INTERNAL_PROVENANCE_KEY, provenance)
                }
            }
        )
    }

    private fun propertyProjection(expr: CypherExpr, variable: String): String? {
        val property = expr as? CypherExpr.Property
        val owner = property?.expression as? CypherExpr.Variable
        return property?.propertyName?.takeIf { owner?.name == variable }
    }

    /**
     * Fast path for:
     *
     *   MATCH (n:Label) WHERE ... RETURN ... LIMIT k
     *
     * This keeps LIMIT semantics on filtered/projected rows while avoiding
     * materializing every node match before WHERE is evaluated.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "MagicNumber", "ReturnCount")
    private fun tryFastFilteredNodeLimit(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size != FILTERED_LIMIT_QUERY_CLAUSES) return null
        val match = clauses[0] as? CypherClause.Match ?: return null
        val where = clauses[1] as? CypherClause.Where ?: return null
        val ret = clauses[2] as? CypherClause.Return ?: return null
        val limit = clauses[3] as? CypherClause.Limit ?: return null
        if (match.optional || match.patterns.size != 1 || ret.items.any { containsAggregation(it.expression) }) {
            return null
        }
        if (ret.items.any { (it.expression as? CypherExpr.Variable)?.name == "*" }) return null

        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) return null

        val nodePattern = pattern.elements.single() as? PatternElement.NodePattern ?: return null
        val variable = nodePattern.variable ?: return null
        val limitCount = literalLimitCount(limit.count) ?: return null
        val columns = ret.items.map { it.alias ?: it.expression.toCypherString() }
        if (limitCount <= 0) return CypherResult(columns, emptyList())

        val nodeClass = nodePattern.labels.firstOrNull()
            ?.let { NodePropertyAccessor.resolveNodeLabel(it) }
            ?: Node::class.java
        val directStringFilter = (where.condition as? CypherExpr.StringOp)
            ?.let { DirectStringFilter.compile(it, variable) }
        if (!ret.distinct && directStringFilter != null && nodePattern.labels.size <= 1 && nodePattern.properties.isEmpty()) {
            return executeDirectStringFilter(
                nodeClass,
                variable,
                directStringFilter,
                ret.items,
                columns,
                limitCount
            )
        }
        if (ret.distinct && nodePattern.labels.size <= 1 && nodePattern.properties.isEmpty()) {
            val directStringDisjunction = DirectStringDisjunction.compile(where.condition, variable)
            if (directStringDisjunction != null) {
                return executeDirectStringDisjunction(
                    nodeClass,
                    variable,
                    directStringDisjunction,
                    ret.items,
                    columns,
                    limitCount
                )
            }
        }

        val rows = mutableListOf<Map<String, Any?>>()
        val distinctRows = if (ret.distinct) {
            LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        } else {
            null
        }
        val predicateBindings = mutableMapOf<String, Any?>(variable to null)
        for (candidate in nodeCandidates(nodeClass)) {
            if (!matchesNodeConstraints(candidate, nodePattern, emptyMap())) continue

            predicateBindings[variable] = candidate
            if (evaluator.evaluate(where.condition, predicateBindings) != true) continue

            val bindings = mutableMapOf<String, Any?>(variable to candidate)
            addProvenance(bindings, candidate)
            val projected = projectRow(ret.items, columns, bindings)
            if (distinctRows == null) {
                rows.add(projected)
                if (rows.size >= limitCount) return CypherResult(columns, rows)
            } else {
                addDistinctRow(distinctRows, projected, limitCount)
                if (!qualified && distinctRows.size >= limitCount) {
                    return CypherResult(columns, distinctRows.values.toList())
                }
            }
        }

        return CypherResult(columns, distinctRows?.values?.toList() ?: rows)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addDistinctRow(
        rows: LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>,
        row: Map<String, Any?>,
        limit: Int
    ) {
        val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
        val existing = rows[visible]
        if (existing != null) {
            val graphIds = (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
            if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
        } else if (rows.size < limit) {
            rows[visible] = row.toMutableMap()
        }
    }

    private fun executeDirectStringFilter(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringFilter,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int
    ): CypherResult {
        val rows = mutableListOf<Map<String, Any?>>()
        for (source in sources) {
            val indexedNodes = stringPropertyCandidates(
                source.graph,
                nodeClass,
                filter,
                limit - rows.size
            )
            val candidates = indexedNodes
                ?: trackWork(source.graph.nodes(nodeClass)).filter(filter::matches)
            for (node in candidates) {

                val candidate = nodeValue(source, node)
                val bindings = mutableMapOf<String, Any?>(variable to candidate)
                addProvenance(bindings, candidate)
                rows.add(projectRow(items, columns, bindings))
                if (rows.size >= limit) return CypherResult(columns, rows)
            }
        }
        return CypherResult(columns, rows)
    }

    private fun executeDirectStringDisjunction(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int
    ): CypherResult {
        if (canExecuteDirectStringDisjunctionInParallel(nodeClass, variable, filter, items)) {
            return executeDirectStringDisjunctionInParallel(
                nodeClass,
                variable,
                filter,
                items,
                columns,
                limit
            )
        }
        return executeDirectStringDisjunctionSerial(nodeClass, variable, filter, items, columns, limit)
    }

    private fun executeDirectStringDisjunctionSerial(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int
    ): CypherResult {
        val rows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        for (source in sources) {
            for (node in directStringCandidates(source.graph, nodeClass, filter)) {
                val candidate = nodeValue(source, node)
                val bindings = mutableMapOf<String, Any?>(variable to candidate)
                addProvenance(bindings, candidate)
                addDistinctRow(rows, projectRow(items, columns, bindings), limit)
                if (!qualified && rows.size >= limit) return CypherResult(columns, rows.values.toList())
            }
        }
        return CypherResult(columns, rows.values.toList())
    }

    private fun canExecuteDirectStringDisjunctionInParallel(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>
    ): Boolean = qualified && sources.size > 1 && directStringParallelism > 1 &&
        !directStringWorkerActive.get() && shouldParallelizeStringScan(nodeClass, filter) && items.all { item ->
        when (val expression = item.expression) {
            is CypherExpr.Literal -> true
            is CypherExpr.Property -> expression.expression == CypherExpr.Variable(variable)
            else -> false
        }
    }

    private fun shouldParallelizeStringScan(
        nodeClass: Class<out Node>,
        filter: DirectStringDisjunction
    ): Boolean = sources.any { source ->
        DIRECT_STRING_NODE_PROPERTIES.any { (candidateType, properties) ->
            if (!nodeClass.isAssignableFrom(candidateType) ||
                filter.filters.none { it.property in properties } || source.graph.nodeCount(candidateType) == 0L
            ) return@any false
            val strategy = source.graph as? StringPropertyDisjunctionLookupStrategy
            strategy?.prefersSerialStringPropertyDisjunction(candidateType) != true
        }
    }

    /**
     * Scans independent graphs concurrently but merges rows in source order.
     * Each scanner pauses after a bounded batch of local distinct rows. Once
     * the global LIMIT is known, workers drain only for those selected values
     * so later occurrences can add complete provenance without retaining an
     * unbounded per-graph result set.
     */
    private fun executeDirectStringDisjunctionInParallel(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int
    ): CypherResult {
        val tracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        val scanners = sources.map { source ->
            DirectStringSourceScanner(source, nodeClass, filter, variable, items, columns, tracker)
        }
        val rows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        var waveStart = 0
        while (waveStart < scanners.size && rows.size < limit) {
            val wave = scanners.subList(waveStart, minOf(scanners.size, waveStart + directStringParallelism))
            val initial = runDirectStringTasks(wave.map { scanner ->
                { scanner.nextDistinctRows(limit) }
            })
            for (index in wave.indices) {
                var batch = initial[index]
                mergeDirectStringBatch(rows, batch.rows, limit)
                while (rows.size < limit && !batch.exhausted) {
                    batch = wave[index].nextDistinctRows(limit)
                    mergeDirectStringBatch(rows, batch.rows, limit)
                }
            }
            waveStart += wave.size
        }

        if (rows.size >= limit) {
            val selected = rows.keys.toSet()
            val selectedMatcher = DirectStringSelectedRowMatcher(selected, items, columns)
            val hits = runDirectStringTasks(scanners.map { scanner ->
                { scanner.collectRemainingSelectedRows(selectedMatcher) }
            })
            hits.forEachIndexed { index, visibleRows ->
                val graphId = scanners[index].source.id
                visibleRows.forEach { visible ->
                    val row = rows.getValue(visible)
                    @Suppress("UNCHECKED_CAST")
                    val provenance = row[INTERNAL_PROVENANCE_KEY] as? Set<String> ?: emptySet()
                    row[INTERNAL_PROVENANCE_KEY] = provenance + graphId
                }
            }
        }
        return CypherResult(columns, rows.values.toList())
    }

    private fun mergeDirectStringBatch(
        target: LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>,
        rows: List<Map<String, Any?>>,
        limit: Int
    ) {
        rows.forEach { row -> addDistinctRow(target, row, limit) }
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun <T> runDirectStringTasks(tasks: List<() -> T>): List<T> {
        if (tasks.size == 1) return listOf(tasks.single().invoke())
        val completionService = ExecutorCompletionService<IndexedValue<T>>(directStringExecutor)
        val futures = arrayOfNulls<Future<IndexedValue<T>>>(tasks.size)
        val completions = arrayOfNulls<CountDownLatch>(tasks.size)
        val started = arrayOfNulls<AtomicBoolean>(tasks.size)
        fun submit(index: Int) {
            val completion = CountDownLatch(1)
            val taskStarted = AtomicBoolean()
            completions[index] = completion
            started[index] = taskStarted
            futures[index] = completionService.submit(Callable {
                if (!taskStarted.compareAndSet(false, true)) {
                    throw java.util.concurrent.CancellationException()
                }
                val previouslyActive = directStringWorkerActive.get()
                directStringWorkerActive.set(true)
                try {
                    IndexedValue(index, tasks[index]())
                } finally {
                    if (previouslyActive) directStringWorkerActive.set(true) else directStringWorkerActive.remove()
                    completion.countDown()
                }
            })
        }
        return try {
            var nextTask = 0
            repeat(minOf(tasks.size, directStringParallelism)) { submit(nextTask++) }
            val results = arrayOfNulls<Any?>(tasks.size)
            repeat(tasks.size) {
                val result = completionService.take().get()
                results[result.index] = result.value
                if (nextTask < tasks.size) submit(nextTask++)
            }
            @Suppress("UNCHECKED_CAST")
            results.map { it as T }
        } catch (error: Throwable) {
            futures.forEachIndexed { index, future ->
                val taskStarted = started[index]
                val completion = completions[index]
                if (future != null && taskStarted != null && completion != null) {
                    if (future.cancel(true) && taskStarted.compareAndSet(false, true)) completion.countDown()
                }
            }
            awaitDirectStringTasks(completions.filterNotNull())
            val cause = (error as? ExecutionException)?.cause ?: error
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Parallel graph scan failed", cause)
            }
        }
    }

    private fun awaitDirectStringTasks(completions: List<CountDownLatch>) {
        var interrupted = false
        completions.forEach { completion ->
            while (true) {
                try {
                    completion.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private inner class DirectStringSourceScanner(
        val source: CypherGraph,
        private val nodeClass: Class<out Node>,
        private val filter: DirectStringDisjunction,
        private val variable: String,
        private val items: List<ReturnItem>,
        private val columns: List<String>,
        private val tracker: CypherWorkTracker?
    ) {
        private val graphLock = directStringGraphLock(source.graph)
        private val localRows = HashSet<Map<String, Any?>>()
        private val iterator by lazy {
            directStringCandidates(source.graph, nodeClass, filter, tracker).iterator()
        }
        private var exhausted = false
        private var inspected = 0

        fun nextDistinctRows(maxRows: Int): DirectStringScanBatch = withGraphLock {
            val rows = mutableListOf<Map<String, Any?>>()
            while (!exhausted && rows.size < maxRows) {
                if (!iterator.hasNext()) {
                    exhausted = true
                    break
                }
                val row = projectParallelSafeNode(iterator.next())
                val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
                if (localRows.add(visible)) rows += row
                pollInterrupted()
            }
            DirectStringScanBatch(rows, exhausted)
        }

        fun collectRemainingSelectedRows(
            selected: DirectStringSelectedRowMatcher
        ): Set<Map<String, Any?>> =
            withGraphLock {
                val hits = mutableSetOf<Map<String, Any?>>()
                val matcher = selected.cursor(source)
                while (!exhausted) {
                    if (!iterator.hasNext()) {
                        exhausted = true
                        break
                    }
                    matcher.match(iterator.next())?.let(hits::add)
                    pollInterrupted()
                }
                hits
            }

        private fun <T> withGraphLock(action: () -> T): T {
            try {
                graphLock.lockInterruptibly()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CypherQueryCancelledException()
            }
            return try {
                action()
            } finally {
                graphLock.unlock()
            }
        }

        private fun projectParallelSafeNode(node: Node): Map<String, Any?> {
            val candidate = nodeValue(source, node)
            return mutableMapOf<String, Any?>().apply {
                items.indices.forEach { index ->
                    this[columns[index]] = when (val expression = items[index].expression) {
                        is CypherExpr.Literal -> expression.value
                        is CypherExpr.Property -> nodeProperty(candidate, expression.propertyName)
                        else -> error("Unsafe expression reached parallel string projection")
                    }
                }
                put(INTERNAL_PROVENANCE_KEY, setOf(source.id))
            }
        }

        private fun pollInterrupted() {
            inspected++
            if ((inspected and CANCELLATION_POLL_MASK) == 0 && Thread.currentThread().isInterrupted) {
                throw CypherQueryCancelledException()
            }
        }
    }

    private data class DirectStringScanBatch(
        val rows: List<Map<String, Any?>>,
        val exhausted: Boolean
    )

    private inner class DirectStringSelectedRowMatcher(
        selected: Set<Map<String, Any?>>,
        items: List<ReturnItem>,
        columns: List<String>
    ) {
        private val rowsByHash = selected.groupBy { it.hashCode() }
        private val projections = LinkedHashMap<String, CypherExpr>().apply {
            items.indices.forEach { index -> this[columns[index]] = items[index].expression }
        }.entries.map { (column, expression) -> column to expression }

        fun cursor(source: CypherGraph): Cursor = Cursor(source)

        inner class Cursor(private val source: CypherGraph) {
            private val values = arrayOfNulls<Any?>(projections.size)

            fun match(node: Node): Map<String, Any?>? {
                val candidate = nodeValue(source, node)
                var hash = 0
                projections.indices.forEach { index ->
                    val (column, expression) = projections[index]
                    val value = projectionValue(candidate, expression)
                    values[index] = value
                    hash += column.hashCode() xor (value?.hashCode() ?: 0)
                }
                return rowsByHash[hash]?.firstOrNull { row ->
                    projections.indices.all { index ->
                        row[projections[index].first] == values[index]
                    }
                }
            }

            private fun projectionValue(candidate: Any, expression: CypherExpr): Any? = when (expression) {
                is CypherExpr.Literal -> expression.value
                is CypherExpr.Property -> nodeProperty(candidate, expression.propertyName)
                else -> error("Unsafe expression reached selected-row matcher")
            }
        }
    }

    private fun directStringCandidates(
        graph: Graph,
        nodeClass: Class<out Node>,
        disjunction: DirectStringDisjunction,
        tracker: CypherWorkTracker? = if (workTrackingEnabled) activeWorkTracker.get() else null
    ): Sequence<Node> {
        val candidateSequences = mutableListOf<Sequence<Node>>()
        for ((candidateType, properties) in DIRECT_STRING_NODE_PROPERTIES) {
            if (!nodeClass.isAssignableFrom(candidateType)) continue
            val filters = disjunction.filters.filter { it.property in properties }
            if (filters.isEmpty()) continue

            val completeScanLimit = graph.nodeCount(candidateType)
                ?.takeIf { it < Int.MAX_VALUE }
                ?.toInt()
                ?: Int.MAX_VALUE
            val fused = stringPropertyDisjunctionCandidates(
                graph,
                candidateType,
                filters,
                completeScanLimit,
                tracker
            )
            if (fused != null) {
                candidateSequences += fused
                continue
            }
            val accelerated = filters.map { filter ->
                stringPropertyCandidates(
                    graph,
                    candidateType,
                    filter,
                    completeScanLimit,
                    tracker
                )
            }
            val candidates: Sequence<Node> = if (accelerated.any { it == null }) {
                interruptible(trackWork(graph.nodes(candidateType), tracker)).filter(disjunction::matches)
            } else if (graph is StringPropertyLookupOrder) {
                mergeNodeSequences(accelerated.filterNotNull(), graph::stringPropertyNodeOrder)
            } else {
                filterOwnedNodes(filters, accelerated.filterNotNull())
            }
            candidateSequences += candidates
        }
        return if (graph is StringPropertyLookupOrder) {
            mergeNodeSequences(candidateSequences, graph::stringPropertyNodeOrder)
        } else {
            candidateSequences.asSequence().flatten()
        }
    }

    private fun <T> interruptible(values: Sequence<T>): Sequence<T> = sequence {
        var inspected = 0
        for (value in values) {
            if ((inspected++ and CANCELLATION_POLL_MASK) == 0 && Thread.currentThread().isInterrupted) {
                throw CypherQueryCancelledException()
            }
            yield(value)
        }
    }

    private fun <T : Node> filterOwnedNodes(
        filters: List<DirectStringFilter>,
        sequences: List<Sequence<T>>
    ): Sequence<Node> = sequence {
        for ((index, nodes) in sequences.withIndex()) {
            for (node in nodes) {
                val ownedByEarlierFilter = (0 until index).any { earlierIndex ->
                    filters[earlierIndex].matches(node)
                }
                if (!ownedByEarlierFilter) yield(node)
            }
        }
    }

    private fun mergeNodeSequences(
        sequences: List<Sequence<Node>>,
        nodeOrder: (Node) -> Long
    ): Sequence<Node> = sequence {
        val cursors = PriorityQueue<NodeSequenceCursor>(
            compareBy<NodeSequenceCursor> { it.nodeOrder }.thenBy { it.sequenceOrder }
        )
        sequences.forEachIndexed { order, nodes ->
            val iterator = nodes.iterator()
            if (iterator.hasNext()) {
                val node = iterator.next()
                cursors += NodeSequenceCursor(order, iterator, node, nodeOrder(node))
            }
        }
        var lastNodeId: Int? = null
        while (cursors.isNotEmpty()) {
            val cursor = cursors.remove()
            val node = cursor.node
            if (node.id.value != lastNodeId) {
                yield(node)
                lastNodeId = node.id.value
            }
            if (cursor.iterator.hasNext()) {
                cursor.node = cursor.iterator.next()
                val nextOrder = nodeOrder(cursor.node)
                require(nextOrder >= cursor.nodeOrder) {
                    "String property lookup sequence is not monotonic in canonical graph order"
                }
                cursor.nodeOrder = nextOrder
                cursors += cursor
            }
        }
    }

    private data class NodeSequenceCursor(
        val sequenceOrder: Int,
        val iterator: Iterator<Node>,
        var node: Node,
        var nodeOrder: Long
    )

    private data class DirectStringFilter(
        val property: String,
        val mode: StringMatchMode,
        val expected: String,
        val transform: StringValueTransform? = null
    ) {
        fun matches(node: Node): Boolean {
            val raw = NodePropertyAccessor.getProperty(node, property) as? String ?: return false
            val actual = when (transform) {
                null -> raw
                StringValueTransform.LOWERCASE -> raw.lowercase()
            }
            return when (mode) {
                StringMatchMode.STARTS_WITH -> actual.startsWith(expected)
                StringMatchMode.ENDS_WITH -> actual.endsWith(expected)
                StringMatchMode.CONTAINS -> actual.contains(expected)
            }
        }

        companion object {
            @Suppress("ReturnCount")
            fun compile(expression: CypherExpr, variable: String): DirectStringFilter? {
                val stringOp = expression as? CypherExpr.StringOp ?: return null
                val operand = compileOperand(stringOp.left) ?: return null
                val property = operand.property
                val owner = property.expression as? CypherExpr.Variable ?: return null
                val literal = stringOp.right as? CypherExpr.Literal ?: return null
                val expected = literal.value as? String ?: return null
                if (owner.name != variable) return null
                if (property.propertyName in QUALIFIED_NODE_PROPERTIES) return null
                if (operand.coalescesMissingToEmpty && expected.isEmpty()) return null
                val mode = when (stringOp.op) {
                    "STARTS WITH" -> StringMatchMode.STARTS_WITH
                    "ENDS WITH" -> StringMatchMode.ENDS_WITH
                    "CONTAINS" -> StringMatchMode.CONTAINS
                    else -> return null
                }
                return DirectStringFilter(property.propertyName, mode, expected, operand.transform)
            }

            @Suppress("ComplexCondition", "ReturnCount")
            private fun compileOperand(expression: CypherExpr): StringOperand? {
                if (expression is CypherExpr.Property) return StringOperand(expression)
                val lower = expression as? CypherExpr.FunctionCall ?: return null
                if (lower.distinct || lower.args.size != 1 ||
                    !(lower.name.equals("toLower", ignoreCase = true) ||
                        lower.name.equals("toLowercase", ignoreCase = true))
                ) {
                    return null
                }
                val argument = lower.args.single()
                if (argument is CypherExpr.Property) {
                    return StringOperand(argument, StringValueTransform.LOWERCASE)
                }
                val coalesce = argument as? CypherExpr.FunctionCall ?: return null
                if (coalesce.distinct || !coalesce.name.equals("coalesce", ignoreCase = true) ||
                    coalesce.args.size != 2 || coalesce.args[1] != CypherExpr.Literal("")
                ) {
                    return null
                }
                val property = coalesce.args[0] as? CypherExpr.Property ?: return null
                return StringOperand(property, StringValueTransform.LOWERCASE, coalescesMissingToEmpty = true)
            }

            private data class StringOperand(
                val property: CypherExpr.Property,
                val transform: StringValueTransform? = null,
                val coalescesMissingToEmpty: Boolean = false
            )
        }
    }

    private data class DirectStringDisjunction(val filters: List<DirectStringFilter>) {
        fun matches(node: Node): Boolean = filters.any { it.matches(node) }

        companion object {
            @Suppress("ReturnCount")
            fun compile(expression: CypherExpr, variable: String): DirectStringDisjunction? {
                val terms = flattenOr(expression)
                if (terms.size < 2) return null
                val filters = terms.map { guardedFilter(it, variable) ?: return null }.distinct()
                if (filters.any { filter ->
                        DIRECT_STRING_NODE_PROPERTIES.none { (_, properties) -> filter.property in properties }
                    }
                ) {
                    return null
                }
                return DirectStringDisjunction(filters)
            }

            private fun flattenOr(expression: CypherExpr): List<CypherExpr> = when (expression) {
                is CypherExpr.Or -> flattenOr(expression.left) + flattenOr(expression.right)
                else -> listOf(expression)
            }

            @Suppress("ReturnCount")
            private fun guardedFilter(expression: CypherExpr, variable: String): DirectStringFilter? {
                DirectStringFilter.compile(expression, variable)?.let { return it }
                val and = expression as? CypherExpr.And ?: return null
                val left = DirectStringFilter.compile(and.left, variable)
                if (left != null && isExistenceGuard(and.right, variable, left.property)) return left
                val right = DirectStringFilter.compile(and.right, variable)
                if (right != null && isExistenceGuard(and.left, variable, right.property)) return right
                return null
            }

            @Suppress("ReturnCount")
            private fun isExistenceGuard(expression: CypherExpr, variable: String, property: String): Boolean {
                val call = expression as? CypherExpr.FunctionCall ?: return false
                if (!call.name.equals("exists", ignoreCase = true) || call.distinct) return false
                val argument = call.args.singleOrNull() as? CypherExpr.Property ?: return false
                return argument.expression == CypherExpr.Variable(variable) && argument.propertyName == property
            }
        }
    }

    /**
     * Fast path for:
     *
     *   MATCH (a:Source)-[:TYPE]->(b:Target) RETURN ... LIMIT k
     *
     * The generic pipeline materializes node and relationship intermediates.
     * For a single non-variable-length hop without filtering/reordering clauses,
     * we can stream complete relationship matches and stop once LIMIT rows have
     * been projected.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    private fun tryFastSingleHopRelationshipLimit(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size != SINGLE_HOP_LIMIT_QUERY_CLAUSES) return null
        val match = clauses[0] as? CypherClause.Match ?: return null
        val ret = clauses[1] as? CypherClause.Return ?: return null
        val limit = clauses[2] as? CypherClause.Limit ?: return null
        if (match.optional || ret.distinct || match.patterns.size != 1 || ret.items.any { containsAggregation(it.expression) }) {
            return null
        }
        if (ret.items.any { (it.expression as? CypherExpr.Variable)?.name == "*" }) return null

        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != SINGLE_HOP_PATTERN_ELEMENTS) return null

        val sourcePattern = pattern.elements[0] as? PatternElement.NodePattern ?: return null
        val rel = pattern.elements[1] as? PatternElement.RelationshipPattern ?: return null
        val targetPattern = pattern.elements[2] as? PatternElement.NodePattern ?: return null
        if (sourcePattern.variable == null || rel.variableLength) return null

        val limitCount = literalLimitCount(limit.count) ?: return null
        val columns = ret.items.map { it.alias ?: it.expression.toCypherString() }
        if (limitCount <= 0) return CypherResult(columns, emptyList())

        val sourceClass = sourcePattern.labels.firstOrNull()
            ?.let { NodePropertyAccessor.resolveNodeLabel(it) }
            ?: Node::class.java
        val edgeClass = rel.types.singleOrNull()?.let { NodePropertyAccessor.resolveEdgeType(it) }

        val rows = mutableListOf<Map<String, Any?>>()
        for (sourceValue in nodeCandidates(sourceClass)) {
            if (!matchesNodeConstraints(sourceValue, sourcePattern, emptyMap())) continue
            val source = nodeCursor(sourceValue) ?: continue

            val sourceBindings = mutableMapOf<String, Any?>(sourcePattern.variable to sourceValue)
            addProvenance(sourceBindings, sourceValue)
            for (edge in edgesForDirection(source, rel.direction, edgeClass)) {
                if (!matchesRelConstraints(edge.edge, rel, sourceBindings)) continue

                val targetId = resolveTargetId(edge.edge, source.node.id, rel.direction)
                val target = trackedNode(source.source.graph, targetId) ?: continue
                val targetValue = nodeValue(source.source, target)
                val targetBindings = matchTargetNode(targetPattern, targetValue, sourceBindings) ?: continue

                val bindings = targetBindings.toMutableMap()
                if (rel.variable != null) bindings[rel.variable] = edge.value
                rows.add(projectRow(ret.items, columns, bindings))
                if (rows.size >= limitCount) return CypherResult(columns, rows)
            }
        }

        return CypherResult(columns, rows)
    }

    private fun projectRow(
        items: List<ReturnItem>,
        columns: List<String>,
        bindings: Map<String, Any?>
    ): Map<String, Any?> {
        val projected = mutableMapOf<String, Any?>()
        for (i in items.indices) {
            projected[columns[i]] = evaluator.evaluate(items[i].expression, bindings)
        }
        copyProvenance(projected, listOf(bindings))
        return projected
    }

    private fun literalLimitCount(expr: CypherExpr): Int? {
        if (expr !is CypherExpr.Literal) return null
        return evaluateToInt(expr, emptyMap())
    }

    // ========================================================================
    // MATCH
    // ========================================================================

    private fun executeMatch(
        patterns: List<CypherPattern>,
        inputRows: List<Map<String, Any?>>,
        limit: Int? = null
    ): List<Map<String, Any?>> {
        var rows = inputRows
        for (pattern in patterns) {
            val nextRows = mutableListOf<Map<String, Any?>>()
            for (inputRow in rows) {
                nextRows.addAll(matchPattern(pattern, inputRow, limit))
                if (limit != null && nextRows.size >= limit) break
            }
            rows = if (limit != null && nextRows.size > limit) nextRows.subList(0, limit) else nextRows
        }
        return rows
    }

    private fun executeOptionalMatch(
        patterns: List<CypherPattern>,
        inputRows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        for (inputRow in inputRows) {
            var currentMatches = listOf(inputRow)

            for (pattern in patterns) {
                val nextMatches = mutableListOf<Map<String, Any?>>()
                for (row in currentMatches) {
                    nextMatches.addAll(matchPattern(pattern, row))
                }
                currentMatches = nextMatches
            }

            if (currentMatches.isEmpty()) {
                // Optional match: null only NEW variables introduced by this pattern,
                // not variables already bound in inputRow
                val newVars = patterns.flatMap { it.variables() }
                    .filter { it !in inputRow }
                val nullBindings = newVars.associateWith { null as Any? }
                results.add(inputRow + nullBindings)
            } else {
                results.addAll(currentMatches)
            }
        }

        return results
    }

    // ========================================================================
    // Pattern matching engine
    // ========================================================================

    /**
     * Match a single [CypherPattern] (which is a chain of node/relationship elements)
     * against the graph, extending the given bindings.
     */
    private fun matchPattern(
        pattern: CypherPattern,
        existingBindings: Map<String, Any?>,
        limit: Int? = null
    ): List<Map<String, Any?>> {
        val elements = pattern.elements
        if (elements.isEmpty()) return listOf(existingBindings)

        val matches = if (limit != null && elements.size > 1) {
            matchPatternLazily(elements, existingBindings, limit)
        } else {
            matchPatternEagerly(elements, existingBindings, limit)
        }

        // If the pattern has a path variable, bind it to the matched path
        if (pattern.pathVariable != null) {
            return matches.map { bindings ->
                val path = buildPathRepresentation(pattern, bindings)
                bindings.toMutableMap().apply {
                    this[pattern.pathVariable] = path
                    addProvenance(this, path)
                }
            }
        }

        return matches
    }

    private fun matchPatternEagerly(
        elements: List<PatternElement>,
        existingBindings: Map<String, Any?>,
        limit: Int?
    ): List<Map<String, Any?>> {
        var currentMatches = matchNodeElement(elements[0] as PatternElement.NodePattern, existingBindings, limit)

        var i = 1
        while (i < elements.size) {
            val rel = elements[i] as PatternElement.RelationshipPattern
            val targetNode = elements[i + 1] as PatternElement.NodePattern
            i += 2

            val nextMatches = mutableListOf<Map<String, Any?>>()
            for (bindings in currentMatches) {
                nextMatches.addAll(matchRelationship(rel, targetNode, bindings, limit = null))
            }
            currentMatches = nextMatches
        }

        return currentMatches
    }

    private fun matchPatternLazily(
        elements: List<PatternElement>,
        existingBindings: Map<String, Any?>,
        limit: Int
    ): List<Map<String, Any?>> {
        var currentMatches = matchNodeElementLazily(
            elements[0] as PatternElement.NodePattern,
            existingBindings
        )

        var i = 1
        while (i < elements.size) {
            val rel = elements[i] as PatternElement.RelationshipPattern
            val targetNode = elements[i + 1] as PatternElement.NodePattern
            i += 2

            val relationshipLimit = limit.takeIf { i >= elements.size }
            val nextMatches = currentMatches.flatMap { bindings ->
                matchRelationship(rel, targetNode, bindings, relationshipLimit).asSequence()
            }
            currentMatches = relationshipLimit?.let(nextMatches::take) ?: nextMatches
        }

        return currentMatches.toList()
    }

    /**
     * Build a path representation as a list of alternating nodes and edges:
     * [startNode, edge1, node2, edge2, ..., endNode]
     *
     * For relationships without explicit variables, we look up edges between
     * consecutive node pairs in the graph.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun buildPathRepresentation(
        pattern: CypherPattern,
        bindings: Map<String, Any?>
    ): Any {
        val path = mutableListOf<Any>()
        val elements = pattern.elements

        for (i in elements.indices) {
            when (val element = elements[i]) {
                is PatternElement.NodePattern -> {
                    val value = element.variable?.let { bindings[it] }
                    if (nodeValue(value) != null && value != null) path.add(value)
                }
                is PatternElement.RelationshipPattern -> {
                    val value = element.variable?.let { bindings[it] }
                    if (edgeValue(value) != null && value != null) {
                        path.add(value)
                    } else {
                        // Look up the edge between the previous and next nodes
                        val prevValue = elements.getOrNull(i - 1)
                            ?.let { it as? PatternElement.NodePattern }
                            ?.variable?.let { bindings[it] }
                        val nextValue = elements.getOrNull(i + 1)
                            ?.let { it as? PatternElement.NodePattern }
                            ?.variable?.let { bindings[it] }
                        val prev = nodeCursor(prevValue)
                        val next = nodeCursor(nextValue)
                        if (prev != null && next != null && prev.source.id == next.source.id) {
                            val foundEdge = trackWork(prev.source.graph.outgoing(prev.node.id))
                                .firstOrNull { it.to == next.node.id }
                                ?: trackWork(prev.source.graph.incoming(prev.node.id))
                                    .firstOrNull { it.from == next.node.id }
                            if (foundEdge != null) path.add(edgeValue(prev.source, foundEdge))
                        }
                    }
                }
            }
        }
        if (!qualified) return path

        val nodes = path.filterIsInstance<QualifiedNode>()
        val edges = path.filterIsInstance<QualifiedEdge>()
        val graphIds = (nodes.map { it.graphId } + edges.map { it.graphId }).distinct()
        return if (graphIds.size == 1) QualifiedPath(graphIds.single(), nodes, edges) else path
    }

    /**
     * Match a [PatternElement.NodePattern] against the graph.
     */
    private fun matchNodeElement(
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>,
        limit: Int? = null
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for (candidate in nodeElementCandidates(nodePattern, existingBindings)) {
            val bindings = bindNodeCandidate(candidate, nodePattern, existingBindings) ?: continue
            results.add(bindings)
            if (limit != null && results.size >= limit) break
        }
        return results
    }

    private fun matchNodeElementLazily(
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>
    ): Sequence<Map<String, Any?>> = nodeElementCandidates(nodePattern, existingBindings)
        .mapNotNull { candidate -> bindNodeCandidate(candidate, nodePattern, existingBindings) }

    private fun nodeElementCandidates(
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>
    ): Sequence<Any> {
        val nodeClass = nodePattern.labels.firstOrNull()
            ?.let { NodePropertyAccessor.resolveNodeLabel(it) }
            ?: Node::class.java

        return if (nodePattern.variable != null &&
            existingBindings.containsKey(nodePattern.variable)
        ) {
            val existing = existingBindings[nodePattern.variable]
            val node = nodeValue(existing)
            if (existing != null && node != null && nodeClass.isInstance(node)) {
                sequenceOf(existing)
            } else {
                emptySequence()
            }
        } else {
            nodeCandidates(nodeClass)
        }
    }

    private fun bindNodeCandidate(
        candidate: Any,
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>
    ): Map<String, Any?>? {
        if (!matchesNodeConstraints(candidate, nodePattern, existingBindings)) return null
        return existingBindings.toMutableMap().apply {
            if (nodePattern.variable != null) {
                this[nodePattern.variable] = candidate
                addProvenance(this, candidate)
            }
        }
    }

    /**
     * Match a relationship + target node from the current source node binding.
     */
    private fun matchRelationship(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>,
        limit: Int?
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        // The source node is the last-bound node in the current bindings.
        val sourceNode = findLastBoundNode(bindings) ?: return results

        val edgeClass = rel.types.firstOrNull()?.let { NodePropertyAccessor.resolveEdgeType(it) }

        if (rel.variableLength) {
            matchVariableLengthPath(rel, targetNodePattern, sourceNode, bindings, edgeClass, limit, results)
        } else {
            matchSingleHop(rel, targetNodePattern, sourceNode, bindings, edgeClass, limit, results)
        }

        return results
    }

    private fun matchSingleHop(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        edgeClass: Class<out Edge>?,
        limit: Int?,
        results: MutableList<Map<String, Any?>>
    ) {
        val edges = edgesForDirection(sourceNode, rel.direction, edgeClass)

        for (edge in edges) {
            val targetId = resolveTargetId(edge.edge, sourceNode.node.id, rel.direction)
            val targetNode = trackedNode(sourceNode.source.graph, targetId) ?: continue
            val targetValue = nodeValue(sourceNode.source, targetNode)

            // Check relationship property constraints
            if (!matchesRelConstraints(edge.edge, rel, bindings)) continue

            val targetMatch = matchTargetNode(targetNodePattern, targetValue, bindings) ?: continue

            val newBindings = targetMatch.toMutableMap()
            if (rel.variable != null) {
                newBindings[rel.variable] = edge.value
                addProvenance(newBindings, edge.value)
            }
            results.add(newBindings)
            if (limit != null && results.size >= limit) break
        }
    }

    private fun matchVariableLengthPath(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        edgeClass: Class<out Edge>?,
        limit: Int?,
        results: MutableList<Map<String, Any?>>
    ) {
        val direction = when (rel.direction) {
            Direction.OUTGOING -> PathFinder.Direction.OUTGOING
            Direction.INCOMING -> PathFinder.Direction.INCOMING
            Direction.BOTH -> PathFinder.Direction.BOTH
        }

        val workTracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        val paths = PathFinder.findPathMatches(
            graph = sourceNode.source.graph,
            sources = setOf(sourceNode.node.id),
            options = PathFinder.SearchOptions(
                targets = null,
                edgeType = edgeClass,
                minDepth = rel.minHops ?: 1,
                maxDepth = rel.maxHops ?: 10,
                direction = direction,
                workTracker = workTracker
            )
        )

        for (pathMatch in paths) {
            val endValue = nodeValue(sourceNode.source, pathMatch.endNode())
            val targetMatch = matchTargetNode(targetNodePattern, endValue, bindings) ?: continue

            val newBindings = targetMatch.toMutableMap()
            if (rel.variable != null) {
                val path = pathMatch.materialize(workTracker)
                val pathValue = if (qualified) {
                    workTracker?.consume(path.nodes.size.toLong() + path.edges.size)
                    QualifiedPath(
                        graphId = sourceNode.source.id,
                        nodes = path.nodes.map { QualifiedNode(sourceNode.source.id, sourceNode.source.graph, it) },
                        edges = path.edges.map { QualifiedEdge(sourceNode.source.id, sourceNode.source.graph, it) }
                    )
                } else {
                    path
                }
                newBindings[rel.variable] = pathValue
                addProvenance(newBindings, pathValue)
            }
            results.add(newBindings)
            if (limit != null && results.size >= limit) break
        }
    }

    @Suppress("ReturnCount")
    private fun matchTargetNode(
        targetPattern: PatternElement.NodePattern,
        value: Any,
        bindings: Map<String, Any?>
    ): Map<String, Any?>? {
        val node = nodeValue(value) ?: return null
        val nodeClass = targetPattern.labels.firstOrNull()
            ?.let { NodePropertyAccessor.resolveNodeLabel(it) }
            ?: Node::class.java

        if (!nodeClass.isInstance(node)) return null

        // Check if already bound to a different node
        if (targetPattern.variable != null && bindings.containsKey(targetPattern.variable)) {
            val existing = bindings[targetPattern.variable]
            if (existing != value) return null
        }

        if (!matchesNodeConstraints(value, targetPattern, bindings)) return null

        val result = bindings.toMutableMap()
        if (targetPattern.variable != null) {
            result[targetPattern.variable] = value
            addProvenance(result, value)
        }
        return result
    }

    @Suppress("ReturnCount")
    private fun matchesNodeConstraints(
        value: Any,
        pattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>
    ): Boolean {
        val node = nodeValue(value) ?: return false
        // Check all labels
        if (pattern.labels.size > 1) {
            val nodeLabels = CypherFunctions.labels(value).map { it.lowercase() }.toSet()
            val allMatch = pattern.labels.all { label ->
                val labelClass = NodePropertyAccessor.resolveNodeLabel(label)
                labelClass.isInstance(node) || label.lowercase() in nodeLabels
            }
            if (!allMatch) return false
        }

        // Check inline property constraints
        return pattern.properties.all { (key, expression) ->
            val exprValue = evaluator.evaluate(expression, bindings)
            nodeProperty(value, key) == exprValue
        }
    }

    private fun matchesRelConstraints(
        edge: Edge,
        rel: PatternElement.RelationshipPattern,
        bindings: Map<String, Any?>
    ): Boolean {
        if (rel.types.isNotEmpty()) {
            val edgeTypeName = CypherFunctions.type(edge)
            val typeMatches = rel.types.any { requested ->
                requested.equals(edgeTypeName, ignoreCase = true) ||
                    (edge is ResourceEdge && requested.equals("RESOURCE", ignoreCase = true))
            }
            if (!typeMatches) {
                return false
            }
        }

        // Check inline property constraints
        return rel.properties.all { (key, value) ->
            val exprValue = evaluator.evaluate(value, bindings)
            val edgeValue = when (edge) {
                is DataFlowEdge -> if (key == "kind") edge.kind.name else null
                is CallEdge -> when (key) {
                    "virtual" -> edge.isVirtual
                    "dynamic" -> edge.isDynamic
                    else -> null
                }
                is TypeEdge -> if (key == "kind") edge.kind.name else null
                is ControlFlowEdge -> if (key == "kind") edge.kind.name else null
                is ResourceEdge -> if (key == "kind") edge.kind.name else null
            }
            edgeValue == exprValue
        }
    }

    // ========================================================================
    // Graph traversal helpers
    // ========================================================================

    private fun edgesForDirection(
        node: NodeCursor,
        direction: Direction,
        edgeClass: Class<out Edge>?
    ): Sequence<EdgeCursor> {
        val graph = node.source.graph
        val nodeId = node.node.id
        val tracked = workTrackingEnabled && activeWorkTracker.get() != null
        val edges = when (direction) {
            Direction.OUTGOING ->
                if (!tracked && edgeClass != null) graph.outgoing(nodeId, edgeClass) else graph.outgoing(nodeId)
            Direction.INCOMING ->
                if (!tracked && edgeClass != null) graph.incoming(nodeId, edgeClass) else graph.incoming(nodeId)
            Direction.BOTH -> {
                val outgoing =
                    if (!tracked && edgeClass != null) graph.outgoing(nodeId, edgeClass) else graph.outgoing(nodeId)
                val incoming =
                    if (!tracked && edgeClass != null) graph.incoming(nodeId, edgeClass) else graph.incoming(nodeId)
                outgoing + incoming
            }
        }
        val candidates = trackWork(edges)
        val filtered = if (tracked && edgeClass != null) {
            candidates.filter(edgeClass::isInstance)
        } else {
            candidates
        }
        return filtered.map { edge -> EdgeCursor(node.source, edge, edgeValue(node.source, edge)) }
    }

    private fun resolveTargetId(edge: Edge, sourceId: NodeId, direction: Direction): NodeId =
        when (direction) {
            Direction.OUTGOING -> edge.to
            Direction.INCOMING -> edge.from
            Direction.BOTH -> if (edge.from == sourceId) edge.to else edge.from
        }

    private fun findLastBoundNode(bindings: Map<String, Any?>): NodeCursor? {
        // Return the last Node value in insertion order
        var last: NodeCursor? = null
        for (value in bindings.values) {
            nodeCursor(value)?.let { last = it }
        }
        return last
    }

    private fun <T : Node> nodeCandidates(type: Class<T>): Sequence<Any> =
        if (qualified) {
            sources.asSequence().flatMap { source ->
                trackWork(source.graph.nodes(type)).map { node -> QualifiedNode(source.id, source.graph, node) }
            }
        } else {
            trackWork(graph.nodes(type)).map { it as Any }
        }

    private fun <T> trackWork(
        values: Sequence<T>,
        tracker: CypherWorkTracker? = if (workTrackingEnabled) activeWorkTracker.get() else null
    ): Sequence<T> {
        return if (!workTrackingEnabled) {
            values
        } else {
            tracker?.let { WorkTrackingSequence(values, it) } ?: values
        }
    }

    private fun trackedNode(graph: Graph, nodeId: NodeId): Node? {
        if (workTrackingEnabled) activeWorkTracker.get()?.consume()
        return graph.node(nodeId)
    }

    private fun <T : Node> stringPropertyCandidates(
        graph: Graph,
        type: Class<T>,
        filter: DirectStringFilter,
        limit: Int,
        tracker: CypherWorkTracker? = if (workTrackingEnabled) activeWorkTracker.get() else null
    ): Sequence<T>? {
        return if (filter.transform == null && tracker != null) {
            graph.nodesByStringProperty(type, filter.property, filter.mode, filter.expected, limit, tracker)
        } else if (filter.transform == null) {
            graph.nodesByStringProperty(type, filter.property, filter.mode, filter.expected, limit)
        } else if (tracker != null) {
            graph.nodesByTransformedStringProperty(
                type,
                filter.property,
                filter.transform,
                filter.mode,
                filter.expected,
                limit,
                tracker
            )
        } else {
            graph.nodesByTransformedStringProperty(
                type,
                filter.property,
                filter.transform,
                filter.mode,
                filter.expected,
                limit
            )
        }
    }

    private fun <T : Node> stringPropertyDisjunctionCandidates(
        graph: Graph,
        type: Class<T>,
        filters: List<DirectStringFilter>,
        limit: Int,
        tracker: CypherWorkTracker? = if (workTrackingEnabled) activeWorkTracker.get() else null
    ): Sequence<T>? {
        val predicates = filters.map { filter ->
            StringPropertyPredicate(filter.property, filter.transform, filter.mode, filter.expected)
        }
        return if (tracker == null) {
            graph.nodesByStringPropertyDisjunction(type, predicates, limit)
        } else {
            graph.nodesByStringPropertyDisjunction(type, predicates, limit, tracker)
        }
    }

    private fun nodeCursor(value: Any?): NodeCursor? = when (value) {
        is QualifiedNode -> NodeCursor(CypherGraph(value.graphId, value.graph), value.node, value)
        is Node -> NodeCursor(sources.single(), value, value)
        else -> null
    }

    private fun nodeValue(source: CypherGraph, node: Node): Any =
        if (qualified) QualifiedNode(source.id, source.graph, node) else node

    private fun edgeValue(source: CypherGraph, edge: Edge): Any =
        if (qualified) QualifiedEdge(source.id, source.graph, edge) else edge

    private fun nodeProperty(value: Any, property: String): Any? = when (value) {
        is QualifiedNode -> when (property) {
            "graphId" -> value.graphId
            "elementId", "qualifiedId" -> value.elementId
            else -> NodePropertyAccessor.getProperty(value.node, property)
        }
        is Node -> NodePropertyAccessor.getProperty(value, property)
        else -> null
    }

    private fun provenanceOf(value: Any?): Set<String> = when (value) {
        is QualifiedNode -> setOf(value.graphId)
        is QualifiedEdge -> setOf(value.graphId)
        is QualifiedPath -> setOf(value.graphId)
        is Iterable<*> -> value.flatMapTo(linkedSetOf()) { provenanceOf(it) }
        is Map<*, *> -> value.values.flatMapTo(linkedSetOf()) { provenanceOf(it) }
        else -> emptySet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun addProvenance(row: MutableMap<String, Any?>, value: Any?) {
        val graphIds = provenanceOf(value)
        if (graphIds.isEmpty()) return
        val existing = row[INTERNAL_PROVENANCE_KEY] as? Set<String> ?: emptySet()
        row[INTERNAL_PROVENANCE_KEY] = existing + graphIds
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyProvenance(target: MutableMap<String, Any?>, inputs: List<Map<String, Any?>>) {
        if (!qualified) return
        val graphIds = linkedSetOf<String>()
        inputs.forEach { row ->
            graphIds.addAll(row[INTERNAL_PROVENANCE_KEY] as? Set<String> ?: emptySet())
        }
        if (graphIds.isNotEmpty()) target[INTERNAL_PROVENANCE_KEY] = graphIds
    }

    @Suppress("UNCHECKED_CAST")
    private fun distinctByVisibleValues(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (!workTrackingEnabled) return distinctByVisibleValuesUntracked(rows)
        val byVisibleValues = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        for ((index, row) in rows.withIndex()) {
            pollCancellation(index)
            val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
            val existing = byVisibleValues[visible]
            if (existing == null) {
                byVisibleValues[visible] = row.toMutableMap()
            } else {
                val graphIds = (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                    (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
                if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
            }
        }
        checkCancelled()
        return byVisibleValues.values.toList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun distinctByVisibleValuesUntracked(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val byVisibleValues = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        for (row in rows) {
            val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
            val existing = byVisibleValues[visible]
            if (existing == null) {
                byVisibleValues[visible] = row.toMutableMap()
            } else {
                val graphIds = (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                    (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
                if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
            }
        }
        return byVisibleValues.values.toList()
    }

    private data class NodeCursor(
        val source: CypherGraph,
        val node: Node,
        val value: Any
    )

    private data class EdgeCursor(
        val source: CypherGraph,
        val edge: Edge,
        val value: Any
    )

    // ========================================================================
    // WHERE
    // ========================================================================

    private fun executeWhere(
        clause: CypherClause.Where,
        rows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        if (!workTrackingEnabled) return rows.filter { row -> evaluator.evaluate(clause.condition, row) == true }
        return rows.filterIndexed { index, row ->
            pollCancellation(index)
            evaluator.evaluate(clause.condition, row) == true
        }
    }

    private fun executeInlineWhere(
        condition: CypherExpr,
        rows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        if (!workTrackingEnabled) return rows.filter { row -> evaluator.evaluate(condition, row) == true }
        return rows.filterIndexed { index, row ->
            pollCancellation(index)
            evaluator.evaluate(condition, row) == true
        }
    }

    // ========================================================================
    // RETURN / WITH (projection + optional aggregation)
    // ========================================================================

    private fun projectAndAggregate(
        items: List<ReturnItem>,
        rows: List<Map<String, Any?>>,
        distinct: Boolean
    ): Pair<List<Map<String, Any?>>, List<String>> {
        // Expand RETURN * into individual variable references for all bound names
        val expandedItems = if (items.size == 1 &&
            items[0].expression is CypherExpr.Variable &&
            (items[0].expression as CypherExpr.Variable).name == "*"
        ) {
            val allKeys = rows.firstOrNull()?.keys?.filter { it != INTERNAL_PROVENANCE_KEY } ?: emptyList()
            allKeys.map { key -> ReturnItem(CypherExpr.Variable(key)) }
        } else {
            items
        }

        val columns = expandedItems.map { it.alias ?: it.expression.toCypherString() }

        // Check if any item uses aggregation
        val hasAggregation = expandedItems.any { containsAggregation(it.expression) }

        val resultRows = if (hasAggregation) {
            // Group by non-aggregated columns
            val groupByIndices = expandedItems.indices.filter { !containsAggregation(expandedItems[it].expression) }
            val aggIndices = expandedItems.indices.filter { containsAggregation(expandedItems[it].expression) }.toSet()

            if (groupByIndices.isEmpty()) {
                // No grouping -- aggregate over all rows
                val row = mutableMapOf<String, Any?>()
                for (i in expandedItems.indices) {
                    val col = columns[i]
                    row[col] = if (i in aggIndices) {
                        evaluateAggregation(expandedItems[i].expression, rows)
                    } else {
                        evaluator.evaluate(expandedItems[i].expression, rows.firstOrNull() ?: emptyMap())
                    }
                }
                copyProvenance(row, rows)
                listOf(row)
            } else {
                // Group by non-aggregated columns
                if (!workTrackingEnabled) {
                    val groups = rows.groupBy { inputRow ->
                        groupByIndices.map { i -> evaluator.evaluate(expandedItems[i].expression, inputRow) }
                    }
                    groups.map { (_, groupRows) ->
                        val row = mutableMapOf<String, Any?>()
                        for (i in expandedItems.indices) {
                            val col = columns[i]
                            row[col] = if (i in aggIndices) {
                                evaluateAggregation(expandedItems[i].expression, groupRows)
                            } else {
                                evaluator.evaluate(expandedItems[i].expression, groupRows.first())
                            }
                        }
                        copyProvenance(row, groupRows)
                        row
                    }
                } else {
                    projectTrackedGroups(expandedItems, columns, groupByIndices, aggIndices, rows)
                }
            }
        } else {
            if (!workTrackingEnabled) {
                rows.map { row ->
                    val projected = mutableMapOf<String, Any?>()
                    for (i in expandedItems.indices) {
                        projected[columns[i]] = evaluator.evaluate(expandedItems[i].expression, row)
                    }
                    copyProvenance(projected, listOf(row))
                    projected
                }
            } else {
                projectTrackedRows(expandedItems, columns, rows)
            }
        }

        val finalRows = if (distinct) distinctByVisibleValues(resultRows) else resultRows
        return finalRows to columns
    }

    private fun projectTrackedGroups(
        items: List<ReturnItem>,
        columns: List<String>,
        groupByIndices: List<Int>,
        aggregateIndices: Set<Int>,
        rows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val groups = LinkedHashMap<List<Any?>, MutableList<Map<String, Any?>>>()
        for ((index, inputRow) in rows.withIndex()) {
            pollCancellation(index)
            val key = groupByIndices.map { i -> evaluator.evaluate(items[i].expression, inputRow) }
            groups.getOrPut(key, ::mutableListOf).add(inputRow)
        }
        return groups.values.mapIndexed { index, groupRows ->
            pollCancellation(index)
            val row = mutableMapOf<String, Any?>()
            for (i in items.indices) {
                row[columns[i]] = if (i in aggregateIndices) {
                    evaluateAggregation(items[i].expression, groupRows)
                } else {
                    evaluator.evaluate(items[i].expression, groupRows.first())
                }
            }
            copyProvenance(row, groupRows)
            row
        }
    }

    private fun projectTrackedRows(
        items: List<ReturnItem>,
        columns: List<String>,
        rows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> = rows.mapIndexed { rowIndex, row ->
        pollCancellation(rowIndex)
        val projected = mutableMapOf<String, Any?>()
        for (i in items.indices) {
            projected[columns[i]] = evaluator.evaluate(items[i].expression, row)
        }
        copyProvenance(projected, listOf(row))
        projected
    }

    private fun containsAggregation(expr: CypherExpr): Boolean = when (expr) {
        is CypherExpr.FunctionCall ->
            CypherFunctions.isAggregation(expr.name) || expr.args.any { containsAggregation(it) }
        is CypherExpr.CountStar -> true
        is CypherExpr.Property -> containsAggregation(expr.expression)
        is CypherExpr.PredicateFunction -> containsAggregation(expr.listExpr) ||
            expr.predicate?.let { containsAggregation(it) } == true
        is CypherExpr.BinaryOp -> containsAggregation(expr.left) || containsAggregation(expr.right)
        is CypherExpr.Comparison -> containsAggregation(expr.left) || containsAggregation(expr.right)
        is CypherExpr.Distinct -> containsAggregation(expr.expression)
        else -> false
    }

    private fun evaluateAggregation(
        expr: CypherExpr,
        rows: List<Map<String, Any?>>
    ): Any? = when (expr) {
        is CypherExpr.CountStar -> rows.size.toLong()
        is CypherExpr.FunctionCall -> {
            if (CypherFunctions.isAggregation(expr.name)) {
                val values = if (workTrackingEnabled) {
                    rows.mapIndexed { index, row ->
                        pollCancellation(index)
                        if (expr.args.isEmpty()) row else evaluator.evaluate(expr.args[0], row)
                    }
                } else {
                    rows.map { row ->
                        if (expr.args.isEmpty()) row else evaluator.evaluate(expr.args[0], row)
                    }
                }
                val filtered = if (expr.distinct) distinctAggregationValues(values) else values
                if (workTrackingEnabled) {
                    CypherFunctions.aggregate(expr.name, filtered, ::checkCancelled)
                } else {
                    CypherFunctions.aggregate(expr.name, filtered)
                }
            } else {
                evaluator.evaluate(expr, rows.firstOrNull() ?: emptyMap())
            }
        }
        is CypherExpr.Distinct -> evaluateAggregation(expr.expression, rows)
        else -> evaluator.evaluate(expr, rows.firstOrNull() ?: emptyMap())
    }

    // ========================================================================
    // UNWIND
    // ========================================================================

    private fun executeUnwind(
        clause: CypherClause.Unwind,
        rows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        if (!workTrackingEnabled) return executeUnwindUntracked(clause, rows)
        val results = mutableListOf<Map<String, Any?>>()
        var processed = 0
        for (row in rows) {
            pollCancellation(processed++)
            val list = evaluator.evaluate(clause.expression, row) as? List<*> ?: continue
            for (element in list) {
                pollCancellation(processed++)
                val newRow = row.toMutableMap()
                newRow[clause.variable] = element
                results.add(newRow)
            }
        }
        checkCancelled()
        return results
    }

    private fun executeUnwindUntracked(
        clause: CypherClause.Unwind,
        rows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for (row in rows) {
            val list = evaluator.evaluate(clause.expression, row) as? List<*> ?: continue
            for (element in list) {
                val newRow = row.toMutableMap()
                newRow[clause.variable] = element
                results.add(newRow)
            }
        }
        return results
    }

    private fun distinctAggregationValues(values: List<Any?>): List<Any?> {
        if (!workTrackingEnabled) return values.distinct()
        val seen = LinkedHashSet<Any?>()
        val distinct = ArrayList<Any?>()
        for ((index, value) in values.withIndex()) {
            pollCancellation(index)
            if (seen.add(value)) distinct.add(value)
        }
        checkCancelled()
        return distinct
    }

    // ========================================================================
    // ORDER BY
    // ========================================================================

    private fun executeOrderBy(
        clause: CypherClause.OrderBy,
        rows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        if (!workTrackingEnabled) {
            return rows.sortedWith(Comparator { left, right ->
                for (item in clause.items) {
                    val leftValue = evaluateOrderValue(item.expression, left)
                    val rightValue = evaluateOrderValue(item.expression, right)
                    val comparison = compareNullable(leftValue, rightValue)
                    if (comparison != 0) return@Comparator if (item.ascending) comparison else -comparison
                }
                0
            })
        }
        var comparisons = 0
        return rows.sortedWith(Comparator { left, right ->
            pollCancellation(comparisons++)
            for (item in clause.items) {
                val leftValue = evaluateOrderValue(item.expression, left)
                val rightValue = evaluateOrderValue(item.expression, right)
                val comparison = compareNullable(leftValue, rightValue)
                if (comparison != 0) return@Comparator if (item.ascending) comparison else -comparison
            }
            0
        })
    }

    private fun evaluateOrderValue(expression: CypherExpr, row: Map<String, Any?>): Any? =
        if (expression is CypherExpr.Variable) row[expression.name] else evaluator.evaluate(expression, row)

    private fun pollCancellation(index: Int) {
        if ((index and CANCELLATION_POLL_MASK) == 0) checkCancelled()
    }

    private fun checkCancelled() {
        if (workTrackingEnabled) activeWorkTracker.get()?.checkCancelled()
    }

    private fun compareNullable(a: Any?, b: Any?): Int = when {
        a == null && b == null -> 0
        a == null -> -1
        b == null -> 1
        a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
        a is String && b is String -> a.compareTo(b)
        a is Boolean && b is Boolean -> a.compareTo(b)
        else -> a.toString().compareTo(b.toString())
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Determine whether LIMIT can be pushed down into pattern matching.
     *
     * This is safe only when the clause sequence between MATCH and LIMIT
     * contains nothing that could filter, reorder, or aggregate rows
     * (i.e. no WHERE, WITH, ORDER BY, UNWIND, SKIP, or aggregation in RETURN).
     * When those intermediate clauses are absent the final row count is
     * bounded by the number of pattern matches, so we can stop scanning early.
     */
    private fun computeEarlyLimit(clauses: List<CypherClause>): Int? {
        val matchIndex = clauses.indexOfFirst { it is CypherClause.Match && !it.optional }
        if (matchIndex < 0) return null

        val limitIndex = clauses.indexOfFirst { it is CypherClause.Limit }
        if (limitIndex <= matchIndex) return null

        // Check that nothing between MATCH and LIMIT invalidates pushdown
        val between = clauses.subList(matchIndex + 1, limitIndex)
        val safe = between.all { clause ->
            when (clause) {
                is CypherClause.Return -> !clause.items.any { containsAggregation(it.expression) } && !clause.distinct
                is CypherClause.Where,
                is CypherClause.With,
                is CypherClause.OrderBy,
                is CypherClause.Skip,
                is CypherClause.Unwind -> false
                else -> true
            }
        }
        if (!safe) return null

        val limitClause = clauses[limitIndex] as CypherClause.Limit
        return evaluateToInt(limitClause.count, emptyMap()).takeIf { it > 0 }
    }

    private fun evaluateToInt(expr: CypherExpr, bindings: Map<String, Any?>): Int {
        val value = evaluator.evaluate(expr, bindings)
        return when (value) {
            is Number -> value.toCypherInt()
            is String -> value.toLongOrNull()
                ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: 0
            else -> 0
        }
    }
}

private fun Number.toCypherInt(): Int = toLong()
    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
    .toInt()

// ========================================================================
// Pattern variable extraction
// ========================================================================

/**
 * Extract all variable names bound by a pattern.
 */
fun CypherPattern.variables(): Set<String> {
    val vars = mutableSetOf<String>()
    pathVariable?.let { vars.add(it) }
    for (element in elements) {
        when (element) {
            is PatternElement.NodePattern -> element.variable?.let { vars.add(it) }
            is PatternElement.RelationshipPattern -> element.variable?.let { vars.add(it) }
        }
    }
    return vars
}
