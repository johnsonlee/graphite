@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "StringLiteralDuplication"
)

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
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.ReleasableStringPropertyDisjunctionCache
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookupStrategy
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregation
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionDistinctProjection
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionProjection
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionAggregation
import io.johnsonlee.graphite.graph.nodesByStringProperty
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import io.johnsonlee.graphite.graph.nodesByTransformedStringProperty
import io.johnsonlee.graphite.graph.methods
import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val COUNT_QUERY_CLAUSES = 2
private const val LABEL_HISTOGRAM_QUERY_CLAUSES = 5
private const val ORDERED_DISTINCT_GRAPH_ID_QUERY_CLAUSES = 4
private const val DISTINCT_LIMIT_QUERY_CLAUSES = 3
private const val FILTERED_LIMIT_QUERY_CLAUSES = 4
private const val SINGLE_HOP_LIMIT_QUERY_CLAUSES = 3
private const val SINGLE_HOP_PATTERN_ELEMENTS = 3
private const val SINGLE_GRAPH_ID = "single"
internal const val MAX_ORDERED_TOP_K_ROWS = 10_000
private const val INTERNAL_MATCHED_PATH_SEGMENTS_KEY = "\u0000graphite.matchedPathSegments"
private const val INTERNAL_CURRENT_NODE_KEY = "\u0000graphite.currentNode"
private const val INTERNAL_PATH_START_NODE_KEY = "\u0000graphite.pathStartNode"
private const val INTERNAL_RELATIONSHIP_MATCH_STATE_KEY = "\u0000graphite.relationshipMatchState"
private const val INTERNAL_ORDER_VALUES_KEY = "\u0000graphite.orderValues"
private val noOpSerialGraphWorkConsumer = SerialGraphWorkBatchConsumer { }
private val noOpParallelGraphWorkConsumer = ParallelGraphWorkBatchConsumer { }

private data class MatchedPathSegment(
    val tail: List<Any>,
    val relationships: List<Edge>
)

private data class RelationshipMatchState(
    val reserved: Set<QualifiedEdge>,
    val used: Set<QualifiedEdge>
)
private const val DIRECT_STRING_PARALLELISM_PROPERTY = "graphite.cypher.directStringParallelism"
private const val MAX_DIRECT_STRING_PARALLELISM = 8
private const val LOW_CORE_DIRECT_STRING_PARALLELISM = 2
private const val LOW_CORE_PROCESSOR_THRESHOLD = 4
private const val DIRECT_ORDER_SOURCE_SHIFT = 56
private typealias DirectNodePredicateFactory = (CypherGraph) -> (Node) -> Boolean

private fun resolveNodeClass(labels: List<String>): Class<out Node>? =
    labels.firstOrNull()?.let(NodePropertyAccessor::resolveNodeLabelOrNull)
        ?: Node::class.java.takeIf { labels.isEmpty() }

private val directStringWorkerNumber = AtomicInteger()
private val directStringWorkerActive = ThreadLocal.withInitial { false }

private val directStringParallelism: Int by lazy {
    resolveDirectStringParallelism(
        System.getProperty(DIRECT_STRING_PARALLELISM_PROPERTY),
        Runtime.getRuntime().availableProcessors()
    )
}

internal fun resolveDirectStringParallelism(configured: String?, availableProcessors: Int): Int {
    val processors = availableProcessors.coerceAtLeast(1)
    // Cross-graph mapped scans compete for memory bandwidth. On low-core hosts, leaving
    // scheduler headroom is faster than filling every reported processor with scan work.
    return configured?.toIntOrNull()?.coerceIn(1, processors) ?: when {
        processors <= LOW_CORE_PROCESSOR_THRESHOLD -> minOf(LOW_CORE_DIRECT_STRING_PARALLELISM, processors)
        else -> minOf(MAX_DIRECT_STRING_PARALLELISM, processors)
    }
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
private val CALL_SITE_DIRECT_STRING_PROPERTIES = setOf(
    "caller_class",
    "caller_name",
    "callee_class",
    "callee_name"
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
    private val activeParameters = ThreadLocal<Map<String, Any?>>()
    private val evaluator = ExpressionEvaluator(
        checkCancelled = if (workTrackingEnabled) ::checkCancelled else null,
        parameterResolver = { name -> activeParameters.get()?.get(name) }
    )

    /**
     * Execute a list of clauses and return the final result.
     */
    fun execute(clauses: List<CypherClause>): CypherResult = execute(clauses, emptyMap(), null)

    internal fun execute(
        clauses: List<CypherClause>,
        workTracker: CypherWorkTracker?
    ): CypherResult = execute(clauses, emptyMap(), workTracker)

    internal fun execute(
        clauses: List<CypherClause>,
        parameters: Map<String, Any?>,
        workTracker: CypherWorkTracker?
    ): CypherResult {
        if (workTracker == null && parameters.isEmpty() && activeParameters.get() == null) {
            return executeWithActiveBudget(clauses)
        }
        val previousTracker = activeWorkTracker.get()
        val previousParameters = activeParameters.get()
        if (workTracker == null) activeWorkTracker.remove() else activeWorkTracker.set(workTracker)
        activeParameters.set(parameters.toMap())
        return try {
            executeWithActiveBudget(clauses)
        } finally {
            if (previousTracker == null) {
                activeWorkTracker.remove()
            } else {
                activeWorkTracker.set(previousTracker)
            }
            if (previousParameters == null) {
                activeParameters.remove()
            } else {
                activeParameters.set(previousParameters)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun executeWithActiveBudget(clauses: List<CypherClause>): CypherResult {
        checkCancelled()
        if (hasUnknownNodeLabel(clauses)) return executeGeneralClauses(clauses)
        if (MethodQueryExecutor.referencesMethod(clauses)) {
            if (activeParameters.get().orEmpty().isEmpty()) {
                val methodResult = MethodQueryExecutor.tryExecute(
                    clauses,
                    sources,
                    qualified,
                    ::checkCancelled,
                    activeWorkTracker.get()
                )
                if (methodResult != null) return methodResult
            }
            graphScopedSources(clauses)?.let { scopedSources ->
                if (scopedSources != sources) {
                    return QueryPipeline(
                        scopedSources,
                        qualified = true,
                        workTrackingEnabled = workTrackingEnabled
                    ).execute(
                        clauses,
                        activeParameters.get().orEmpty(),
                        activeWorkTracker.get()
                    )
                }
            }
            return executeGeneralClauses(clauses)
        }
        graphScopedSources(clauses)?.let { scopedSources ->
            if (scopedSources != sources) {
                return QueryPipeline(
                    scopedSources,
                    qualified = true,
                    workTrackingEnabled = workTrackingEnabled
                ).execute(
                    clauses,
                    activeParameters.get().orEmpty(),
                    activeWorkTracker.get()
                )
            }
        }
        val fastResult = tryFastNodeCount(clauses)
            ?: tryFastLabelHistogram(clauses)
            ?: tryFastOrderedPropertyLimit(clauses)
            ?: tryFastDistinctPropertyLimit(clauses)
            ?: tryFastFilteredStringCount(clauses)
            ?: tryFastFilteredNodeLimit(clauses)
            ?: tryStreamingFilteredNodeMatch(clauses)
            ?: tryStreamingFilteredMatchLimit(clauses)
            ?: tryFastSingleHopRelationshipLimit(clauses)
        if (fastResult != null) return fastResult

        return executeGeneralClauses(clauses)
    }

    /**
     * Restricts a single connected MATCH to graph ids proven by conjunctive predicates.
     * Multiple/disconnected MATCH patterns remain unscoped because their variables may
     * legally bind values from different graphs.
     */
    private fun graphScopedSources(clauses: List<CypherClause>): List<CypherGraph>? {
        val matchIndex = clauses.indices.filter { clauses[it] is CypherClause.Match }.singleOrNull()
            ?: return null
        val match = clauses[matchIndex] as CypherClause.Match
        val pattern = match.patterns.singleOrNull() ?: return null
        val variables = pattern.elements.filterIsInstance<PatternElement.NodePattern>()
            .mapNotNullTo(linkedSetOf(), PatternElement.NodePattern::variable)
        if (variables.isEmpty()) return null
        val condition = match.where
            ?: (clauses.getOrNull(matchIndex + 1) as? CypherClause.Where)?.condition
            ?: return null
        val graphIds = graphIdEqualities(condition, variables)
        if (graphIds.isEmpty()) return null
        if (graphIds.size > 1) return emptyList()
        val graphId = graphIds.single()
        return sources.filter { source -> source.id == graphId }
    }

    private fun hasUnknownNodeLabel(clauses: List<CypherClause>): Boolean = clauses.any { clause ->
        clause is CypherClause.Match && clause.patterns.any { pattern ->
            pattern.elements.filterIsInstance<PatternElement.NodePattern>().any { node ->
                node.labels.any { label ->
                    !MethodQueryExecutor.isMethodLabel(label) &&
                        NodePropertyAccessor.resolveNodeLabelOrNull(label) == null
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun executeGeneralClauses(clauses: List<CypherClause>): CypherResult {
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
                        executeOptionalMatch(clause.patterns, clause.where, rows)
                    } else {
                        executeMatch(clause.patterns, rows, earlyLimit)
                    }
                    if (!clause.optional && clause.where != null) {
                        rows = executeInlineWhere(clause.where, rows)
                    }
                }
                is CypherClause.Where -> {
                    if (clauseIndex != consumedWhereIndex) rows = executeWhere(clause, rows)
                }
                is CypherClause.Return -> {
                    val orderBy = clauses.getOrNull(clauseIndex + 1) as? CypherClause.OrderBy
                    val (newRows, newColumns) = projectAndAggregate(clause.items, rows, clause.distinct, orderBy)
                    rows = newRows
                    columns = newColumns
                }
                is CypherClause.With -> {
                    val orderBy = clauses.getOrNull(clauseIndex + 1) as? CypherClause.OrderBy
                    val (newRows, newColumns) = projectAndAggregate(clause.items, rows, clause.distinct, orderBy)
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
        val nodeClass = resolveNodeClass(nodePattern.labels) ?: return emptyList()
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

        val nodeClass = resolveNodeClass(nodePattern.labels) ?: return null
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
        tryFastOrderedDistinctGraphIdLimit(clauses)?.let { return it }
        val query = OrderedPropertyLimitQuery.compile(clauses) ?: return null
        if (query.limit <= 0) return CypherResult(query.columns, emptyList())

        var comparisons = 0
        val comparator = Comparator<RankedProjectedRow> { left, right ->
            if (workTrackingEnabled) pollCancellation(comparisons++)
            for (sort in query.sortItems) {
                val comparison = compareOrderValues(
                    left.row[sort.column],
                    right.row[sort.column],
                    sort.ascending
                )
                if (comparison != 0) {
                    return@Comparator comparison
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
        val encounterOrder: Long,
        val sortValues: List<Any?> = emptyList()
    )

    /**
     * Answers graph catalog discovery without materializing every node in every source.
     *
     * `graphId` is constant within a qualified source and source ids are unique, so the
     * first matching node proves the sole distinct value contributed by that source.
     */
    @Suppress("ComplexCondition", "ReturnCount")
    private fun tryFastOrderedDistinctGraphIdLimit(clauses: List<CypherClause>): CypherResult? {
        if (!qualified || clauses.size != ORDERED_DISTINCT_GRAPH_ID_QUERY_CLAUSES) return null
        val match = clauses[0] as? CypherClause.Match ?: return null
        val ret = clauses[1] as? CypherClause.Return ?: return null
        val orderBy = clauses[2] as? CypherClause.OrderBy ?: return null
        val limit = clauses[3] as? CypherClause.Limit ?: return null
        if (match.optional || match.where != null || match.patterns.size != 1 || !ret.distinct ||
            ret.items.size != 1 || orderBy.items.size != 1
        ) return null

        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) return null
        val node = pattern.elements.single() as? PatternElement.NodePattern ?: return null
        val variable = node.variable ?: return null
        if (node.labels.isNotEmpty() || node.properties.isNotEmpty()) return null

        val item = ret.items.single()
        val property = item.expression as? CypherExpr.Property ?: return null
        if (property.expression != CypherExpr.Variable(variable) || property.propertyName != GRAPH_ID_PROPERTY) {
            return null
        }
        val column = item.alias ?: item.expression.toCypherString()
        val sort = orderBy.items.single()
        if (sort.expression != CypherExpr.Variable(column)) return null

        val limitCount = literalLimitCount(limit.count) ?: return null
        if (limitCount <= 0) return CypherResult(listOf(column), emptyList())
        val orderedSources = if (sort.ascending) {
            sources.sortedBy(CypherGraph::id)
        } else {
            sources.sortedByDescending(CypherGraph::id)
        }
        val rows = ArrayList<Map<String, Any?>>(minOf(limitCount, sources.size))
        for (source in orderedSources) {
            checkCancelled()
            val nodeCount = source.graph.nodeCount(Node::class.java)
            val hasNode = nodeCount?.let { it > 0L } ?: run {
                val candidates = trackWork(source.graph.nodes(Node::class.java)).iterator()
                candidates.hasNext() && candidates.next().let { true }
            }
            if (!hasNode) continue
            rows += linkedMapOf<String, Any?>(
                column to source.id,
                INTERNAL_PROVENANCE_KEY to setOf(source.id)
            )
            if (rows.size >= limitCount) break
        }
        return CypherResult(listOf(column), rows)
    }

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
                if (limitCount > MAX_ORDERED_TOP_K_ROWS) return null
                val nodeClass = resolveNodeClass(nodePattern.labels) ?: return null
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

        val column = returnItem.alias ?: returnItem.expression.toCypherString()
        val nodeClass = resolveNodeClass(nodePattern.labels)
            ?: return CypherResult(listOf(column), emptyList())
        val seen = LinkedHashMap<Any, Pair<Any?, Set<String>>>()
        for (candidate in nodeCandidates(nodeClass)) {
            val variable = nodePattern.variable
            val value = evaluator.evaluate(
                CypherExpr.Property(CypherExpr.Variable(variable), propertyName),
                mapOf(variable to candidate)
            )
            val provenance = provenanceOf(candidate)
            val valueKey = cypherValueKey(value)
            if (valueKey in seen) {
                val (firstValue, existingProvenance) = seen.getValue(valueKey)
                seen[valueKey] = firstValue to (existingProvenance + provenance)
            } else if (seen.size < limitCount) {
                seen[valueKey] = value to provenance
            }
            // A qualified query must keep scanning so duplicate values in
            // later graphs contribute to the row's complete provenance.
            if (!qualified && seen.size >= limitCount) break
        }
        return CypherResult(
            columns = listOf(column),
            rows = seen.values.map { (value, provenance) ->
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

    /** Streams COUNT and COUNT(DISTINCT ...) over indexed string candidates without MATCH materialization. */
    @Suppress("ComplexCondition", "ReturnCount", "UNCHECKED_CAST")
    private fun tryFastFilteredStringCount(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size != 3) return null
        val match = clauses[0] as? CypherClause.Match ?: return null
        val where = clauses[1] as? CypherClause.Where ?: return null
        val ret = clauses[2] as? CypherClause.Return ?: return null
        if (match.optional || match.patterns.size != 1 || ret.distinct || ret.items.size != 1) return null
        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) return null
        val nodePattern = pattern.elements.single() as? PatternElement.NodePattern ?: return null
        if (nodePattern.labels.size > 1 || nodePattern.properties.isNotEmpty()) return null
        val variable = nodePattern.variable ?: return null
        val countExpression = ret.items.single().expression
        val column = ret.items.single().alias ?: countExpression.toCypherString()
        val nodeClass = resolveNodeClass(nodePattern.labels) ?: return CypherResult(
            listOf(column),
            listOf(mapOf(column to 0L))
        )
        val countedExpression = when (countExpression) {
            CypherExpr.CountStar -> null
            is CypherExpr.FunctionCall -> countExpression.args.singleOrNull()
                ?.takeIf { countExpression.name.equals("count", ignoreCase = true) }
                ?: return null
            else -> return null
        }
        val parameters = activeParameters.get().orEmpty()
        val candidatePlan = DirectStringCandidatePlan.compile(where.condition, variable, parameters) ?: return null
        val candidateSources = graphIdEquality(where.condition, variable)
            ?.let { graphId -> sources.filter { it.id == graphId } }
            ?: sources
        val distinct = (countExpression as? CypherExpr.FunctionCall)?.distinct == true
        val exactCandidatePredicate = DirectStringFilter.compile(where.condition, variable, parameters) != null ||
            DirectStringDisjunction.compile(where.condition, variable, parameters) != null
        val rawCountExpression = countedExpression == null || countedExpression is CypherExpr.Property &&
            countedExpression.expression == CypherExpr.Variable(variable) &&
            countedExpression.propertyName !in QUALIFIED_NODE_PROPERTIES
        val useRawNodes = exactCandidatePredicate && rawCountExpression
        val sharedWorkTracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        val parallel = qualified && candidateSources.size > 1 && directStringParallelism > 1 &&
            !directStringWorkerActive.get()
        val partials = if (parallel) {
            runDirectStringTasks(candidateSources.map { source ->
                {
                    filteredStringCountPartial(
                        source,
                        nodeClass,
                        variable,
                        candidatePlan,
                        where.condition,
                        countedExpression,
                        distinct,
                        useRawNodes,
                        parameters,
                        sharedWorkTracker
                    )
                }
            })
        } else {
            candidateSources.map { source ->
                filteredStringCountPartial(
                    source,
                    nodeClass,
                    variable,
                    candidatePlan,
                    where.condition,
                    countedExpression,
                    distinct,
                    useRawNodes,
                    parameters,
                    sharedWorkTracker
                )
            }
        }
        val distinctValues = if (distinct) HashSet<Any>() else null
        val count = if (distinctValues == null) {
            partials.sumOf(FilteredStringCountPartial::count)
        } else {
            partials.forEach { partial -> distinctValues.addAll(partial.distinctValues.orEmpty()) }
            distinctValues.size.toLong()
        }
        val row = mutableMapOf<String, Any?>(column to count)
        if (qualified) {
            partials.mapNotNullTo(linkedSetOf()) { partial ->
                partial.sourceId.takeIf { partial.matchedWhere }
            }.takeIf { it.isNotEmpty() }?.let { graphIds -> row[INTERNAL_PROVENANCE_KEY] = graphIds }
        }
        return CypherResult(listOf(column), listOf(row))
    }

    @Suppress("LongParameterList")
    private fun filteredStringCountPartial(
        source: CypherGraph,
        nodeClass: Class<out Node>,
        variable: String,
        candidatePlan: DirectStringCandidatePlan,
        condition: CypherExpr,
        countedExpression: CypherExpr?,
        distinct: Boolean,
        useRawNodes: Boolean,
        parameters: Map<String, Any?>,
        workTracker: CypherWorkTracker?
    ): FilteredStringCountPartial {
        if (useRawNodes) return exactStringCountPartial(
            source,
            nodeClass,
            candidatePlan.candidates,
            countedExpression,
            distinct,
            workTracker
        )
        val localEvaluator = if (directStringWorkerActive.get()) {
            ExpressionEvaluator(
                parameterResolver = parameters::get,
                checkCancelled = {
                    if (Thread.currentThread().isInterrupted) throw CypherQueryCancelledException()
                }
            )
        } else {
            evaluator
        }
        val bindings = mutableMapOf<String, Any?>(variable to null)
        val distinctValues = if (distinct) HashSet<Any>() else null
        var count = 0L
        var matchedWhere = false
        var inspected = 0
        for (node in directStringCandidates(source.graph, nodeClass, candidatePlan.candidates, workTracker)) {
            val candidate = if (useRawNodes) node else nodeValue(source, node)
            bindings[variable] = candidate
            if (!useRawNodes && localEvaluator.evaluate(condition, bindings) != true) continue
            matchedWhere = true
            if (countedExpression == null) {
                count++
            } else {
                val value = localEvaluator.evaluate(countedExpression, bindings) ?: continue
                if (distinctValues == null || distinctValues.add(cypherValueKey(value))) count++
            }
            if ((inspected++ and CANCELLATION_POLL_MASK) == 0 && Thread.currentThread().isInterrupted) {
                throw CypherQueryCancelledException()
            }
        }
        return FilteredStringCountPartial(source.id, count, distinctValues, matchedWhere)
    }

    @Suppress("LongParameterList")
    private fun exactStringCountPartial(
        source: CypherGraph,
        nodeClass: Class<out Node>,
        disjunction: DirectStringDisjunction,
        countedExpression: CypherExpr?,
        distinct: Boolean,
        workTracker: CypherWorkTracker?
    ): FilteredStringCountPartial {
        val countedProperty = (countedExpression as? CypherExpr.Property)?.propertyName
        val distinctValues = if (distinct) HashSet<Any>() else null
        val storageAggregation = source.graph as? StringPropertyDisjunctionAggregation
        var count = 0L
        var matchedWhere = false
        for ((candidateType, properties) in DIRECT_STRING_NODE_PROPERTIES) {
            if (!nodeClass.isAssignableFrom(candidateType)) continue
            val filters = disjunction.filters.filter { it.property in properties }
            if (filters.isEmpty()) continue
            val predicates = filters.map { filter ->
                StringPropertyPredicate(filter.property, filter.transform, filter.mode, filter.expected)
            }
            val canAggregateProperty = countedExpression == null || countedProperty in properties
            val aggregate = if (canAggregateProperty) {
                if (workTracker == null) {
                    storageAggregation?.aggregateStringPropertyDisjunction(
                        candidateType,
                        predicates,
                        countedProperty.takeIf { distinct }
                    )
                } else {
                    (storageAggregation as? WorkAwareStringPropertyDisjunctionAggregation)
                        ?.aggregateStringPropertyDisjunction(
                            candidateType,
                            predicates,
                            countedProperty.takeIf { distinct },
                            workTracker
                        )
                }
            } else {
                null
            }
            if (aggregate != null) {
                if (aggregate.count > 0L) matchedWhere = true
                if (distinctValues == null) {
                    count += aggregate.count
                } else {
                    aggregate.distinctValues.orEmpty().mapTo(distinctValues, ::cypherValueKey)
                }
                continue
            }

            val candidates = directStringCandidates(
                source.graph,
                candidateType,
                DirectStringDisjunction(filters),
                workTracker
            )
            for (node in candidates) {
                matchedWhere = true
                if (countedExpression == null) {
                    count++
                } else {
                    val value = countedProperty?.let { property ->
                        NodePropertyAccessor.getProperty(node, property)
                    } ?: continue
                    if (distinctValues == null || distinctValues.add(cypherValueKey(value))) count++
                }
            }
        }
        if (distinctValues != null) count = distinctValues.size.toLong()
        return FilteredStringCountPartial(source.id, count, distinctValues, matchedWhere)
    }

    private data class FilteredStringCountPartial(
        val sourceId: String,
        val count: Long,
        val distinctValues: Set<Any>?,
        val matchedWhere: Boolean
    )

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

        val nodeClass = resolveNodeClass(nodePattern.labels) ?: return CypherResult(columns, emptyList())
        val routedGraphId = graphIdEquality(where.condition, variable)
        val candidateSources = routedGraphId
            ?.let { graphId -> sources.filter { it.id == graphId } }
            ?: sources
        val filterCondition = routedGraphId
            ?.takeIf { graphId -> graphIdEqualities(where.condition, setOf(variable)) == setOf(graphId) }
            ?.let { graphId -> stripConjunctiveGraphIdEquality(where.condition, variable, graphId) }
            ?: where.condition
        val stringParameters = activeParameters.get().orEmpty()
        val directStringFilter = DirectStringFilter.compile(filterCondition, variable, stringParameters)
        if (!ret.distinct && directStringFilter != null && nodePattern.labels.size <= 1 && nodePattern.properties.isEmpty()) {
            return executeDirectStringFilter(
                nodeClass,
                variable,
                directStringFilter,
                ret.items,
                columns,
                limitCount,
                candidateSources
            )
        }
        if (ret.distinct && directStringFilter != null && nodePattern.labels.size <= 1 &&
            nodePattern.properties.isEmpty() && hasTypedDirectStringCandidate(nodeClass, directStringFilter)
        ) {
            return executeDirectStringDisjunction(
                nodeClass,
                variable,
                DirectStringDisjunction(listOf(directStringFilter)),
                ret.items,
                columns,
                limitCount,
                candidateSources = candidateSources
            )
        }
        if (nodePattern.labels.size <= 1 && nodePattern.properties.isEmpty()) {
            val directStringDisjunction = DirectStringDisjunction.compile(
                filterCondition,
                variable,
                stringParameters
            )
            if (directStringDisjunction != null) {
                return if (ret.distinct) {
                    executeDirectStringDisjunction(
                        nodeClass,
                        variable,
                        directStringDisjunction,
                        ret.items,
                        columns,
                        limitCount,
                        candidateSources = candidateSources
                    )
                } else {
                    executeDirectStringDisjunctionRows(
                        nodeClass,
                        variable,
                        directStringDisjunction,
                        ret.items,
                        columns,
                        limitCount,
                        candidateSources = candidateSources
                    )
                }
            }
            val directStringConjunction = DirectStringConjunction.compile(
                filterCondition,
                variable,
                stringParameters
            )
            if (directStringConjunction != null) {
                val candidates = DirectStringDisjunction(listOf(directStringConjunction.required))
                val predicateFactory: DirectNodePredicateFactory = { _ ->
                    { node: Node -> directStringConjunction.matches(node) }
                }
                return if (ret.distinct) {
                    executeDirectStringDisjunction(
                        nodeClass,
                        variable,
                        candidates,
                        ret.items,
                        columns,
                        limitCount,
                        predicateFactory,
                        candidateSources
                    )
                } else {
                    executeDirectStringDisjunctionRows(
                        nodeClass,
                        variable,
                        candidates,
                        ret.items,
                        columns,
                        limitCount,
                        predicateFactory,
                        candidateSources
                    )
                }
            }
            val candidatePlan = DirectStringCandidatePlan.compile(
                filterCondition,
                variable,
                stringParameters
            )
            if (candidatePlan != null) {
                val tracker = if (workTrackingEnabled) activeWorkTracker.get() else null
                val predicateFactory: DirectNodePredicateFactory = { source ->
                    val predicateBindings = mutableMapOf<String, Any?>(variable to null)
                    val localEvaluator = ExpressionEvaluator(
                        parameterResolver = stringParameters::get,
                        checkCancelled = {
                            tracker?.checkCancelled()
                            if (Thread.currentThread().isInterrupted) throw CypherQueryCancelledException()
                        }
                    )
                    val predicate: (Node) -> Boolean = { node ->
                        predicateBindings[variable] = nodeValue(source, node)
                        localEvaluator.evaluate(filterCondition, predicateBindings) == true
                    }
                    predicate
                }
                return if (ret.distinct) {
                    executeDirectStringDisjunction(
                        nodeClass,
                        variable,
                        candidatePlan.candidates,
                        ret.items,
                        columns,
                        limitCount,
                        predicateFactory,
                        candidateSources
                    )
                } else {
                    executeDirectStringDisjunctionRows(
                        nodeClass,
                        variable,
                        candidatePlan.candidates,
                        ret.items,
                        columns,
                        limitCount,
                        predicateFactory,
                        candidateSources
                    )
                }
            }
        }

        val rows = mutableListOf<Map<String, Any?>>()
        val distinctRows = if (ret.distinct) {
            LinkedHashMap<Any, MutableMap<String, Any?>>()
        } else {
            null
        }
        val predicateBindings = mutableMapOf<String, Any?>(variable to null)
        for (candidate in nodeCandidates(nodeClass, candidateSources)) {
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
                addCypherDistinctRow(distinctRows, projected, limitCount)
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

    @Suppress("UNCHECKED_CAST")
    private fun addCypherDistinctRow(
        rows: LinkedHashMap<Any, MutableMap<String, Any?>>,
        row: Map<String, Any?>,
        limit: Int
    ) {
        val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
        val key = cypherValueKey(visible)
        val existing = rows[key]
        if (existing != null) {
            val graphIds = (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
            if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
        } else if (rows.size < limit) {
            rows[key] = row.toMutableMap()
        }
    }

    private fun executeDirectStringFilter(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringFilter,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int,
        candidateSources: List<CypherGraph>
    ): CypherResult {
        val hasTypedCandidate = hasTypedDirectStringCandidate(nodeClass, filter)
        if (hasTypedCandidate) {
            return executeDirectStringDisjunctionRows(
                nodeClass,
                variable,
                DirectStringDisjunction(listOf(filter)),
                items,
                columns,
                limit,
                candidateSources = candidateSources
            )
        }

        val rows = mutableListOf<Map<String, Any?>>()
        for (source in candidateSources) {
            val candidates = stringPropertyCandidates(
                source.graph,
                nodeClass,
                filter,
                limit - rows.size
            ) ?: trackWork(source.graph.nodes(nodeClass)).filter(filter::matches)
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

    private fun hasTypedDirectStringCandidate(
        nodeClass: Class<out Node>,
        filter: DirectStringFilter
    ): Boolean = DIRECT_STRING_NODE_PROPERTIES.any { (candidateType, properties) ->
        nodeClass.isAssignableFrom(candidateType) && filter.property in properties
    }

    @Suppress("LongParameterList")
    private fun executeDirectStringDisjunction(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory? = null,
        candidateSources: List<CypherGraph> = sources
    ): CypherResult {
        if (canExecuteDirectStringDisjunctionInParallel(nodeClass, variable, filter, items, candidateSources)) {
            return executeDirectStringDisjunctionInParallel(
                nodeClass,
                variable,
                filter,
                items,
                columns,
                limit,
                nodePredicateFactory,
                candidateSources
            )
        }
        return executeDirectStringDisjunctionSerial(
            nodeClass,
            variable,
            filter,
            items,
            columns,
            limit,
            nodePredicateFactory,
            candidateSources
        )
    }

    @Suppress("LongParameterList", "NestedBlockDepth", "ReturnCount")
    private fun executeDirectStringDisjunctionRows(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory? = null,
        candidateSources: List<CypherGraph> = sources
    ): CypherResult {
        executeIndexedStringProjectionRows(
            nodeClass,
            variable,
            filter,
            items,
            columns,
            limit,
            nodePredicateFactory,
            candidateSources
        )?.let { return it }
        if (workTrackingEnabled ||
            !canExecuteDirectStringDisjunctionInParallel(nodeClass, variable, filter, items, candidateSources)
        ) {
            val rows = mutableListOf<Map<String, Any?>>()
            for (source in candidateSources) {
                val nodePredicate = nodePredicateFactory?.invoke(source)
                val candidates = directStringCandidates(
                    source.graph,
                    nodeClass,
                    filter,
                    limit = if (nodePredicate == null) limit - rows.size else Int.MAX_VALUE
                )
                    .let { nodes -> nodePredicate?.let { predicate -> nodes.filter(predicate) } ?: nodes }
                for (node in candidates) {
                    val candidate = nodeValue(source, node)
                    val bindings = mutableMapOf<String, Any?>(variable to candidate)
                    addProvenance(bindings, candidate)
                    rows += projectRow(items, columns, bindings)
                    if (rows.size >= limit) return CypherResult(columns, rows)
                }
            }
            return CypherResult(columns, rows)
        }

        val tracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        val scanners = candidateSources.map { source ->
            DirectStringSourceScanner(
                source,
                nodeClass,
                filter,
                variable,
                items,
                columns,
                tracker,
                nodePredicateFactory
            )
        }
        val rows = mutableListOf<Map<String, Any?>>()
        var waveStart = 0
        while (waveStart < scanners.size && rows.size < limit) {
            val wave = scanners.subList(waveStart, minOf(scanners.size, waveStart + directStringParallelism))
            val batches = runDirectStringTasks(wave.map { scanner ->
                { scanner.nextRows(limit) }
            })
            batches.forEach { batch ->
                val remaining = limit - rows.size
                if (remaining > 0) rows += batch.take(remaining)
            }
            waveStart += wave.size
        }
        return CypherResult(columns, rows)
    }

    @Suppress("LongParameterList", "ReturnCount")
    private fun executeIndexedStringProjectionRows(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory?,
        candidateSources: List<CypherGraph>
    ): CypherResult? {
        if ((nodeClass != CallSiteNode::class.java && nodeClass != Node::class.java) ||
            nodePredicateFactory != null || candidateSources.size != 1
        ) {
            return null
        }
        val source = candidateSources.single()
        if (nodeClass == Node::class.java && source.graph.nodeCount(AnnotationNode::class.java) != 0L) return null
        val projectedProperties = items.map { item ->
            val property = item.expression as? CypherExpr.Property ?: return null
            if (property.expression != CypherExpr.Variable(variable) ||
                property.propertyName !in CALL_SITE_DIRECT_STRING_PROPERTIES
            ) {
                return null
            }
            property.propertyName
        }
        val predicates = filter.filters.map { candidate ->
            if (candidate.property !in CALL_SITE_DIRECT_STRING_PROPERTIES) return null
            StringPropertyPredicate(candidate.property, candidate.transform, candidate.mode, candidate.expected)
        }
        val projection = source.graph as? StringPropertyDisjunctionProjection ?: return null
        val tracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        val projectedRows = projection.projectStringPropertyDisjunction(
            CallSiteNode::class.java,
            predicates,
            projectedProperties,
            limit,
            tracker
        ) ?: return null
        return DirectProjectionResultCache.getOrCreate(projectedRows, columns, source.id)
    }

    @Suppress("LongParameterList")
    private fun executeDirectStringDisjunctionSerial(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory?,
        candidateSources: List<CypherGraph>
    ): CypherResult {
        val rows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        for (source in candidateSources) {
            val nodePredicate = nodePredicateFactory?.invoke(source)
            val candidates = directStringCandidates(source.graph, nodeClass, filter)
                .let { nodes -> nodePredicate?.let { predicate -> nodes.filter(predicate) } ?: nodes }
            for (node in candidates) {
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
        items: List<ReturnItem>,
        candidateSources: List<CypherGraph>
    ): Boolean = qualified && candidateSources.size > 1 && directStringParallelism > 1 &&
        !directStringWorkerActive.get() &&
        shouldParallelizeStringScan(nodeClass, filter, candidateSources) && items.all { item ->
        when (val expression = item.expression) {
            is CypherExpr.Literal -> true
            is CypherExpr.Property -> expression.expression == CypherExpr.Variable(variable)
            else -> false
        }
    }

    private fun shouldParallelizeStringScan(
        nodeClass: Class<out Node>,
        filter: DirectStringDisjunction,
        candidateSources: List<CypherGraph> = sources
    ): Boolean = DIRECT_STRING_NODE_PROPERTIES.any { (candidateType, properties) ->
        if (!nodeClass.isAssignableFrom(candidateType)) return@any false
        val predicates = filter.filters
            .filter { it.property in properties }
            .map { StringPropertyPredicate(it.property, it.transform, it.mode, it.expected) }
        predicates.isNotEmpty() && candidateSources.any { source ->
            if (source.graph.nodeCount(candidateType) == 0L) return@any false
            val strategy = source.graph as? StringPropertyDisjunctionLookupStrategy
            strategy?.prefersSerialStringPropertyDisjunction(candidateType, predicates) != true
        }
    }

    /**
     * Scans independent graphs concurrently but merges rows in source order.
     * Each scanner pauses after a bounded batch of local distinct rows. Once
     * the global LIMIT is known, workers drain only for those selected values
     * so later occurrences can add complete provenance without retaining an
     * unbounded per-graph result set.
     */
    @Suppress("LongParameterList")
    private fun executeDirectStringDisjunctionInParallel(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory?,
        candidateSources: List<CypherGraph>
    ): CypherResult {
        if (nodePredicateFactory == null) {
            executeIndexedDistinctStringProjection(
                nodeClass,
                variable,
                filter,
                items,
                columns,
                limit,
                candidateSources
            )?.let { return it }
        }
        val tracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        val scanners = candidateSources.map { source ->
            DirectStringSourceScanner(
                source,
                nodeClass,
                filter,
                variable,
                items,
                columns,
                tracker,
                nodePredicateFactory
            )
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

    @Suppress("LongMethod", "NestedBlockDepth", "ReturnCount")
    private fun executeIndexedDistinctStringProjection(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        columns: List<String>,
        limit: Int,
        candidateSources: List<CypherGraph> = sources
    ): CypherResult? {
        if (!nodeClass.isAssignableFrom(CallSiteNode::class.java)) return null
        val projectedProperties = items.map { item ->
            val property = item.expression as? CypherExpr.Property ?: return null
            if (property.expression != CypherExpr.Variable(variable)) return null
            property.propertyName
        }
        if (projectedProperties.any { property ->
                property != GRAPH_ID_PROPERTY && property !in CALL_SITE_DIRECT_STRING_PROPERTIES
            }
        ) return null
        val callSiteFilters = filter.filters.filter { it.property in CALL_SITE_DIRECT_STRING_PROPERTIES }
        if (callSiteFilters.isEmpty()) return null
        val predicates = callSiteFilters.map { candidate ->
            StringPropertyPredicate(candidate.property, candidate.transform, candidate.mode, candidate.expected)
        }
        val projections = candidateSources.map { source ->
            source.graph as? StringPropertyDisjunctionDistinctProjection ?: return null
        }
        val orders = candidateSources.map { source -> source.graph as? StringPropertyLookupOrder ?: return null }
        val tracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        val storageWorkConsumer = stringStorageWorkConsumer(candidateSources.size, tracker)

        val rows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        val zeroHitSources = BooleanArray(candidateSources.size)
        var waveStart = 0
        while (waveStart < candidateSources.size && rows.size < limit) {
            val waveEnd = minOf(candidateSources.size, waveStart + directStringParallelism)
            val localRows = runDirectStringTasks((waveStart until waveEnd).map { sourceIndex ->
                {
                    val source = candidateSources[sourceIndex]
                    val projected = projections[sourceIndex].distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        projectedProperties,
                        limit,
                        selectedValues = null,
                        workConsumer = storageWorkConsumer
                    ) ?: error("Distinct projection capability became unavailable")
                    val sourceRows = projected.map { raw ->
                        OrderedProjectedRow(
                            raw.encounterOrder,
                            rawProjectionRow(raw.values, projectedProperties, columns, source.id)
                        )
                    }.toMutableList()

                    val seenGeneric = HashSet<Map<String, Any?>>()
                    for (node in directStringCandidates(
                        source.graph,
                        nodeClass,
                        filter,
                        tracker,
                        excludedTypes = setOf(CallSiteNode::class.java)
                    )) {
                        val row = projectedNodeRow(source, node, projectedProperties, columns)
                        val visible = visibleRow(row)
                        if (seenGeneric.add(visible)) {
                            sourceRows += OrderedProjectedRow(orders[sourceIndex].stringPropertyNodeOrder(node), row)
                            if (seenGeneric.size >= limit) break
                        }
                    }
                    val distinct = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
                    sourceRows.sortedBy(OrderedProjectedRow::encounterOrder).forEach { ordered ->
                        addDistinctRow(distinct, ordered.row, limit)
                    }
                    distinct.values.mapIndexed { index, row -> OrderedProjectedRow(index.toLong(), row) }
                }
            })
            localRows.forEachIndexed { localIndex, sourceRows ->
                if (sourceRows.isEmpty()) {
                    val sourceIndex = waveStart + localIndex
                    zeroHitSources[sourceIndex] = true
                    (candidateSources[sourceIndex].graph as? ReleasableStringPropertyDisjunctionCache)
                        ?.releaseStringPropertyDisjunctionCache()
                }
            }
            localRows.forEach { sourceRows ->
                sourceRows.forEach { ordered -> addDistinctRow(rows, ordered.row, limit) }
            }
            waveStart = waveEnd
        }
        if (rows.size < limit) return CypherResult(columns, rows.values.toList())

        val selected = rows.keys.toSet()
        val selectedValues = selected.mapNotNullTo(linkedSetOf()) { row ->
            val values = columns.map(row::get)
            if (values.all { value -> value == null || value is String }) {
                @Suppress("UNCHECKED_CAST")
                values as List<String?>
            } else {
                null
            }
        }
        val selectedMatcher = DirectStringSelectedRowMatcher(selected, items, columns)
        val provenanceSourceIndexes = candidateSources.indices.filterNot { sourceIndex ->
            zeroHitSources[sourceIndex]
        }
        val hits = runDirectStringTasks(provenanceSourceIndexes.map { sourceIndex ->
            {
                val source = candidateSources[sourceIndex]
                val sourceSelectedValues = storageSelectedValues(selectedValues, projectedProperties, source.id)
                val rawHits = mutableSetOf<Map<String, Any?>>()
                if (sourceSelectedValues.isNotEmpty()) {
                    projections[sourceIndex].distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        projectedProperties,
                        sourceSelectedValues.size,
                        sourceSelectedValues,
                        storageWorkConsumer
                    ).orEmpty().mapTo(rawHits) { hit ->
                        visibleRow(rawProjectionRow(hit.values, projectedProperties, columns, source.id))
                    }
                }
                val matcher = selectedMatcher.cursor(source)
                for (node in directStringCandidates(
                    source.graph,
                    nodeClass,
                    filter,
                    tracker,
                    excludedTypes = setOf(CallSiteNode::class.java)
                )) {
                    matcher.match(node)?.let(rawHits::add)
                    if (rawHits.size >= selected.size) break
                }
                rawHits
            }
        })
        hits.forEachIndexed { hitIndex, visibleRows ->
            val sourceIndex = provenanceSourceIndexes[hitIndex]
            val graphId = candidateSources[sourceIndex].id
            visibleRows.forEach { visible ->
                val row = rows.getValue(visible)
                @Suppress("UNCHECKED_CAST")
                val provenance = row[INTERNAL_PROVENANCE_KEY] as? Set<String> ?: emptySet()
                row[INTERNAL_PROVENANCE_KEY] = provenance + graphId
            }
        }
        return CypherResult(columns, rows.values.toList())
    }

    private fun rawProjectionRow(
        values: List<String?>,
        projectedProperties: List<String>,
        columns: List<String>,
        graphId: String
    ): MutableMap<String, Any?> = linkedMapOf<String, Any?>().apply {
        columns.indices.forEach { index ->
            this[columns[index]] = if (projectedProperties[index] == GRAPH_ID_PROPERTY) graphId else values[index]
        }
        put(INTERNAL_PROVENANCE_KEY, setOf(graphId))
    }

    private fun storageSelectedValues(
        selectedValues: Set<List<String?>>,
        projectedProperties: List<String>,
        graphId: String
    ): Set<List<String?>> {
        val graphIdIndexes = projectedProperties.indices.filter { index ->
            projectedProperties[index] == GRAPH_ID_PROPERTY
        }
        if (graphIdIndexes.isEmpty()) return selectedValues
        return selectedValues.mapNotNullTo(linkedSetOf()) { values ->
            if (graphIdIndexes.any { index -> values[index] != graphId }) {
                null
            } else {
                values.mapIndexed { index, value ->
                    if (index in graphIdIndexes) null else value
                }
            }
        }
    }

    private fun projectedNodeRow(
        source: CypherGraph,
        node: Node,
        projectedProperties: List<String>,
        columns: List<String>
    ): MutableMap<String, Any?> = linkedMapOf<String, Any?>().apply {
        columns.indices.forEach { index ->
            this[columns[index]] = if (projectedProperties[index] == GRAPH_ID_PROPERTY) {
                source.id
            } else {
                NodePropertyAccessor.getProperty(node, projectedProperties[index])
            }
        }
        put(INTERNAL_PROVENANCE_KEY, setOf(source.id))
    }

    private fun visibleRow(row: Map<String, Any?>): Map<String, Any?> =
        row.filterKeys { key -> key != INTERNAL_PROVENANCE_KEY }

    private data class OrderedProjectedRow(
        val encounterOrder: Long,
        val row: MutableMap<String, Any?>
    )

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
        private val tracker: CypherWorkTracker?,
        nodePredicateFactory: DirectNodePredicateFactory? = null
    ) {
        private val nodePredicate = nodePredicateFactory?.invoke(source)
        private val localRows = HashSet<Map<String, Any?>>()
        private val iterator by lazy {
            directStringCandidates(source.graph, nodeClass, filter, tracker)
                .let { nodes -> nodePredicate?.let { predicate -> nodes.filter(predicate) } ?: nodes }
                .iterator()
        }
        private var exhausted = false
        private var inspected = 0

        fun nextDistinctRows(maxRows: Int): DirectStringScanBatch {
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
            return DirectStringScanBatch(rows, exhausted)
        }

        fun nextRows(maxRows: Int): List<Map<String, Any?>> {
            val rows = mutableListOf<Map<String, Any?>>()
            while (!exhausted && rows.size < maxRows) {
                if (!iterator.hasNext()) {
                    exhausted = true
                    break
                }
                rows += projectParallelSafeNode(iterator.next())
                pollInterrupted()
            }
            return rows
        }

        fun collectRemainingSelectedRows(
            selected: DirectStringSelectedRowMatcher
        ): Set<Map<String, Any?>> {
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
            return hits
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
        tracker: CypherWorkTracker? = if (workTrackingEnabled) activeWorkTracker.get() else null,
        excludedTypes: Set<Class<out Node>> = emptySet(),
        limit: Int = Int.MAX_VALUE
    ): Sequence<Node> {
        if (limit <= 0) return emptySequence()
        val candidateSequences = mutableListOf<Sequence<Node>>()
        for ((candidateType, properties) in DIRECT_STRING_NODE_PROPERTIES) {
            if (candidateType in excludedTypes) continue
            if (!nodeClass.isAssignableFrom(candidateType)) continue
            val filters = disjunction.filters.filter { it.property in properties }
            if (filters.isEmpty()) continue

            val completeScanLimit = graph.nodeCount(candidateType)
                ?.takeIf { it < Int.MAX_VALUE }
                ?.toInt()
                ?.let { count -> minOf(count, limit) }
                ?: limit
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
                StringMatchMode.EQUALS -> actual == expected
                StringMatchMode.STARTS_WITH -> actual.startsWith(expected)
                StringMatchMode.ENDS_WITH -> actual.endsWith(expected)
                StringMatchMode.CONTAINS -> actual.contains(expected)
            }
        }

        companion object {
            @Suppress("ReturnCount")
            fun compile(
                expression: CypherExpr,
                variable: String,
                parameters: Map<String, Any?> = emptyMap()
            ): DirectStringFilter? {
                val (left, right, mode) = when (expression) {
                    is CypherExpr.StringOp -> Triple(
                        expression.left,
                        expression.right,
                        when (expression.op) {
                            "STARTS WITH" -> StringMatchMode.STARTS_WITH
                            "ENDS WITH" -> StringMatchMode.ENDS_WITH
                            "CONTAINS" -> StringMatchMode.CONTAINS
                            else -> return null
                        }
                    )
                    is CypherExpr.Comparison -> {
                        if (expression.op != "=") return null
                        Triple(expression.left, expression.right, StringMatchMode.EQUALS)
                    }
                    else -> return null
                }
                val operand = compileOperand(left) ?: return null
                val property = operand.property
                val owner = property.expression as? CypherExpr.Variable ?: return null
                val expected = stringConstant(right, parameters) ?: return null
                if (owner.name != variable) return null
                if (property.propertyName in QUALIFIED_NODE_PROPERTIES) return null
                if (operand.coalescesMissingToEmpty && expected.isEmpty()) return null
                return DirectStringFilter(property.propertyName, mode, expected, operand.transform)
            }

            fun compileRegexCandidate(
                expression: CypherExpr,
                variable: String,
                parameters: Map<String, Any?>
            ): DirectStringFilter? {
                val regex = expression as? CypherExpr.RegexMatch ?: return null
                val pattern = stringConstant(regex.right, parameters) ?: return null
                val expected = regexContainsLiteral(pattern) ?: return null
                return compile(
                    CypherExpr.StringOp("CONTAINS", regex.left, CypherExpr.Literal(expected)),
                    variable,
                    parameters
                )
            }

            private fun stringConstant(expression: CypherExpr, parameters: Map<String, Any?>): String? =
                when (expression) {
                    is CypherExpr.Literal -> expression.value as? String
                    is CypherExpr.Parameter -> parameters[expression.name] as? String
                    else -> null
                }

            private fun regexContainsLiteral(pattern: String): String? {
                val quotedPrefix = ".*\\Q"
                val quotedSuffix = "\\E.*"
                if (!pattern.startsWith(quotedPrefix) || !pattern.endsWith(quotedSuffix)) return null
                val literal = pattern.substring(quotedPrefix.length, pattern.length - quotedSuffix.length)
                return literal.takeIf { it.isNotEmpty() && "\\E" !in it }
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
            fun compile(
                expression: CypherExpr,
                variable: String,
                parameters: Map<String, Any?> = emptyMap()
            ): DirectStringDisjunction? {
                val terms = flattenOr(expression)
                val filters = terms.flatMap { term ->
                    guardedFilters(term, variable, parameters) ?: return null
                }.distinct()
                if (filters.isEmpty()) return null
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
            private fun guardedFilters(
                expression: CypherExpr,
                variable: String,
                parameters: Map<String, Any?>
            ): List<DirectStringFilter>? {
                DirectStringFilter.compile(expression, variable, parameters)?.let { return listOf(it) }
                val membership = expression as? CypherExpr.ListOp
                if (membership?.op.equals("IN", ignoreCase = true)) {
                    val values = membership?.right as? CypherExpr.ListLiteral ?: return null
                    return values.elements.map { value ->
                        DirectStringFilter.compile(
                            CypherExpr.Comparison("=", membership.left, value),
                            variable,
                            parameters
                        ) ?: return null
                    }
                }
                val and = expression as? CypherExpr.And ?: return null
                val left = DirectStringFilter.compile(and.left, variable, parameters)
                if (left != null && isExistenceGuard(and.right, variable, left.property)) return listOf(left)
                val right = DirectStringFilter.compile(and.right, variable, parameters)
                if (right != null && isExistenceGuard(and.left, variable, right.property)) return listOf(right)
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

    private data class DirectStringConjunction(
        val required: DirectStringFilter,
        val anyOf: DirectStringDisjunction
    ) {
        fun matches(node: Node): Boolean = required.matches(node) && anyOf.matches(node)

        companion object {
            @Suppress("ReturnCount")
            fun compile(
                expression: CypherExpr,
                variable: String,
                parameters: Map<String, Any?> = emptyMap()
            ): DirectStringConjunction? {
                val and = expression as? CypherExpr.And ?: return null
                val left = DirectStringFilter.compile(and.left, variable, parameters)
                val right = DirectStringFilter.compile(and.right, variable, parameters)
                if (left != null && right != null) {
                    val (required, residual) = if (
                        right.mode == StringMatchMode.EQUALS && left.mode != StringMatchMode.EQUALS
                    ) {
                        right to left
                    } else {
                        left to right
                    }
                    return DirectStringConjunction(required, DirectStringDisjunction(listOf(residual)))
                }
                return compile(and.left, and.right, variable, parameters)
                    ?: compile(and.right, and.left, variable, parameters)
            }

            private fun compile(
                requiredExpression: CypherExpr,
                disjunctionExpression: CypherExpr,
                variable: String,
                parameters: Map<String, Any?>
            ): DirectStringConjunction? = DirectStringFilter.compile(requiredExpression, variable, parameters)
                ?.takeIf { required ->
                    DIRECT_STRING_NODE_PROPERTIES.any { (_, properties) -> required.property in properties }
                }
                ?.let { required ->
                    DirectStringDisjunction.compile(disjunctionExpression, variable, parameters)
                        ?.let { anyOf -> DirectStringConjunction(required, anyOf) }
                }
        }
    }

    private data class DirectStringCandidatePlan(val candidates: DirectStringDisjunction) {
        companion object {
            fun compile(
                expression: CypherExpr,
                variable: String,
                parameters: Map<String, Any?>
            ): DirectStringCandidatePlan? {
                DirectStringFilter.compile(expression, variable, parameters)?.let { filter ->
                    if (DIRECT_STRING_NODE_PROPERTIES.any { (_, properties) -> filter.property in properties }) {
                        return DirectStringCandidatePlan(DirectStringDisjunction(listOf(filter)))
                    }
                }
                DirectStringDisjunction.compile(expression, variable, parameters)?.let { disjunction ->
                    return DirectStringCandidatePlan(disjunction)
                }
                DirectStringFilter.compileRegexCandidate(expression, variable, parameters)?.let { filter ->
                    if (DIRECT_STRING_NODE_PROPERTIES.any { (_, properties) -> filter.property in properties }) {
                        return DirectStringCandidatePlan(DirectStringDisjunction(listOf(filter)))
                    }
                }
                val and = expression as? CypherExpr.And ?: return null
                return listOfNotNull(
                    compile(and.left, variable, parameters),
                    compile(and.right, variable, parameters)
                ).minByOrNull(DirectStringCandidatePlan::selectivityRank)
            }
        }

        private fun selectivityRank(): Int = candidates.filters.sumOf { filter ->
            val modeRank = when (filter.mode) {
                StringMatchMode.EQUALS -> 0
                StringMatchMode.STARTS_WITH -> 100
                StringMatchMode.ENDS_WITH -> 200
                StringMatchMode.CONTAINS -> 300
            }
            val propertyRank = when (filter.property) {
                "caller_name", "callee_name", "name" -> 0
                else -> 20
            }
            modeRank + propertyRank - filter.expected.length.coerceAtMost(64)
        }
    }

    /**
     * Streams an unbounded, filtered single-node MATCH through projection.
     *
     * The general pipeline materializes every MATCH binding before applying
     * WHERE. Besides retaining rows that the predicate will discard, that can
     * exhaust a work budget after allocating one binding map per scanned node.
     * Reusing the predicate binding preserves scan work accounting while only
     * allocating result rows for actual matches.
     */
    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "MagicNumber", "ReturnCount")
    private fun tryStreamingFilteredNodeMatch(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size != 3) return null
        val match = clauses[0] as? CypherClause.Match ?: return null
        val where = clauses[1] as? CypherClause.Where ?: return null
        val ret = clauses[2] as? CypherClause.Return ?: return null
        if (match.optional || match.where != null || match.patterns.size != 1 || ret.distinct ||
            ret.items.any { containsAggregation(it.expression) } ||
            ret.items.any { (it.expression as? CypherExpr.Variable)?.name == "*" }
        ) {
            return null
        }

        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) return null
        val nodePattern = pattern.elements.single() as? PatternElement.NodePattern ?: return null
        val variable = nodePattern.variable ?: return null
        if (elementIdEquality(where.condition, variable) != null) return null
        val columns = ret.items.map { it.alias ?: it.expression.toCypherString() }
        val predicateBindings = mutableMapOf<String, Any?>(variable to null)
        val rows = mutableListOf<Map<String, Any?>>()
        val candidateSources = graphIdEquality(where.condition, variable)
            ?.let { graphId -> sources.filter { it.id == graphId } }
            ?: sources

        for (candidate in nodeElementCandidates(nodePattern, emptyMap(), candidateSources)) {
            if (!matchesNodeConstraints(candidate, nodePattern, emptyMap())) continue
            predicateBindings[variable] = candidate
            if (evaluator.evaluate(where.condition, predicateBindings) != true) continue

            val bindings = mutableMapOf<String, Any?>(variable to candidate)
            addProvenance(bindings, candidate)
            rows.add(projectRow(ret.items, columns, bindings))
        }
        checkCancelled()
        return CypherResult(columns, rows)
    }

    /**
     * Streams a filtered MATCH pattern through projection, ordering, and pagination so
     * LIMIT bounds retained rows instead of being applied after eager materialization.
     * DISTINCT and ORDER BY retain at most SKIP + LIMIT visible values.
     */
    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "MagicNumber", "ReturnCount")
    private fun tryStreamingFilteredMatchLimit(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size !in 4..6) return null
        val match = clauses.getOrNull(0) as? CypherClause.Match ?: return null
        val where = clauses.getOrNull(1) as? CypherClause.Where ?: return null
        val ret = clauses.getOrNull(2) as? CypherClause.Return ?: return null
        var suffixIndex = 3
        val orderBy = (clauses.getOrNull(suffixIndex) as? CypherClause.OrderBy)?.also { suffixIndex++ }
        val skip = (clauses.getOrNull(suffixIndex) as? CypherClause.Skip)?.also { suffixIndex++ }
        val limit = clauses.getOrNull(suffixIndex) as? CypherClause.Limit ?: return null
        if (suffixIndex != clauses.lastIndex) return null
        if (match.optional || match.patterns.size != 1 || match.patterns.single().pathVariable != null ||
            ret.items.any { containsAggregation(it.expression) } ||
            ret.items.any { (it.expression as? CypherExpr.Variable)?.name == "*" }
        ) {
            return null
        }

        val limitCount = literalLimitCount(limit.count) ?: return null
        val skipCount = skip?.count?.let(::literalLimitCount) ?: 0
        if (skipCount < 0) return null
        val columns = ret.items.map { it.alias ?: it.expression.toCypherString() }
        if (limitCount <= 0) return CypherResult(columns, emptyList())
        val retainedCount = skipCount.toLong() + limitCount
        if (retainedCount > Int.MAX_VALUE) return null

        val pattern = match.patterns.single()
        val firstNode = pattern.elements.firstOrNull() as? PatternElement.NodePattern
        val candidateSources = firstNode?.variable
            ?.let { variable -> graphIdEquality(where.condition, variable) }
            ?.let { graphId -> sources.filter { it.id == graphId } }
            ?: sources

        val directMatches = (pattern.elements.singleOrNull() as? PatternElement.NodePattern)?.let { node ->
            directStringBindings(node, where.condition, candidateSources)
        }
        if (orderBy == null && ret.distinct) {
            val directNode = pattern.elements.singleOrNull() as? PatternElement.NodePattern
            val variable = directNode?.variable
            if (directNode != null && variable != null && directNode.labels.size <= 1 &&
                directNode.properties.isEmpty()
            ) {
                val nodeClass = resolveNodeClass(directNode.labels)
                val disjunction = DirectStringDisjunction.compile(
                    where.condition,
                    variable,
                    activeParameters.get().orEmpty()
                )
                if (nodeClass != null && disjunction != null) {
                    executeIndexedDistinctStringProjection(
                        nodeClass,
                        variable,
                        disjunction,
                        ret.items,
                        columns,
                        retainedCount.toInt()
                    )?.let { indexed ->
                        return CypherResult(columns, indexed.rows.drop(skipCount))
                    }
                }
            }
        }
        if (orderBy != null) {
            val directNode = pattern.elements.singleOrNull() as? PatternElement.NodePattern
            if (directNode != null) {
                projectOrderedDirectStringRows(
                    directNode,
                    where,
                    ret,
                    orderBy,
                    columns,
                    skipCount,
                    retainedCount.toInt(),
                    candidateSources
                )?.let { return it }
            }
        }
        val matches = directMatches ?: matchPatternLazily(
            pattern,
            emptyMap(),
            candidateSources,
            finalResultPredicate = { bindings -> evaluator.evaluate(where.condition, bindings) == true }
        )
        if (orderBy != null) {
            return projectOrderedFilteredRows(
                matches,
                where,
                ret,
                orderBy,
                columns,
                skipCount,
                retainedCount.toInt()
            )
        }
        if (ret.distinct) {
            return projectDistinctFilteredRows(matches, where, ret, columns, skipCount, retainedCount.toInt())
        }

        val rows = mutableListOf<Map<String, Any?>>()
        var matchedRows = 0
        for (bindings in matches) {
            if (evaluator.evaluate(where.condition, bindings) != true) continue
            if (matchedRows++ < skipCount) continue
            rows.add(projectRow(ret.items, columns, bindings))
            if (rows.size >= limitCount) break
        }
        return CypherResult(columns, rows)
    }

    private fun directStringBindings(
        nodePattern: PatternElement.NodePattern,
        condition: CypherExpr,
        candidateSources: List<CypherGraph>
    ): Sequence<Map<String, Any?>>? {
        if (nodePattern.labels.size > 1 || nodePattern.properties.isNotEmpty()) return null
        val variable = nodePattern.variable ?: return null
        val nodeClass = resolveNodeClass(nodePattern.labels) ?: return emptySequence()
        val plan = DirectStringCandidatePlan.compile(
            condition,
            variable,
            activeParameters.get().orEmpty()
        ) ?: return null
        return candidateSources.asSequence().flatMap { source ->
            directStringCandidates(source.graph, nodeClass, plan.candidates).map { node ->
                mutableMapOf<String, Any?>(variable to nodeValue(source, node)).also { bindings ->
                    addProvenance(bindings, bindings[variable])
                }
            }
        }
    }

    @Suppress("LongParameterList", "NestedBlockDepth", "ReturnCount")
    private fun projectOrderedDirectStringRows(
        nodePattern: PatternElement.NodePattern,
        where: CypherClause.Where,
        ret: CypherClause.Return,
        orderBy: CypherClause.OrderBy,
        columns: List<String>,
        skipCount: Int,
        retainedCount: Int,
        candidateSources: List<CypherGraph>
    ): CypherResult? {
        if (ret.distinct || nodePattern.labels.size > 1 || nodePattern.properties.isNotEmpty()) return null
        val variable = nodePattern.variable ?: return null
        val nodeClass = resolveNodeClass(nodePattern.labels) ?: return CypherResult(columns, emptyList())
        val parameters = activeParameters.get().orEmpty()
        val plan = DirectStringCandidatePlan.compile(where.condition, variable, parameters) ?: return null
        if (candidateSources.size <= 1 || directStringParallelism <= 1 ||
            directStringWorkerActive.get() || !shouldParallelizeStringScan(nodeClass, plan.candidates)
        ) {
            return null
        }

        val comparator = rankedRowComparator(orderBy)
        val topRows = PriorityQueue<RankedProjectedRow>(retainedCount, comparator.reversed())
        val tracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        var waveStart = 0
        while (waveStart < candidateSources.size) {
            val waveEnd = minOf(candidateSources.size, waveStart + directStringParallelism)
            val localRows = runDirectStringTasks((waveStart until waveEnd).map { sourceIndex ->
                {
                    val source = candidateSources[sourceIndex]
                    val localEvaluator = ExpressionEvaluator(
                        parameterResolver = parameters::get,
                        checkCancelled = {
                            if (Thread.currentThread().isInterrupted) throw CypherQueryCancelledException()
                        }
                    )
                    val bindings = mutableMapOf<String, Any?>(variable to null)
                    val localComparator = rankedRowComparator(orderBy)
                    val localTopRows = PriorityQueue<RankedProjectedRow>(retainedCount, localComparator.reversed())
                    var localOrder = 0L
                    for (node in directStringCandidates(source.graph, nodeClass, plan.candidates, tracker)) {
                        val candidate = nodeValue(source, node)
                        bindings[variable] = candidate
                        if (localEvaluator.evaluate(where.condition, bindings) != true) continue
                        val projected = mutableMapOf<String, Any?>().also { row ->
                            ret.items.indices.forEach { index ->
                                row[columns[index]] = localEvaluator.evaluate(ret.items[index].expression, bindings)
                            }
                            addProvenance(row, candidate)
                        }
                        val orderContext = bindings.toMutableMap().apply { putAll(projected) }
                        val ranked = RankedProjectedRow(
                            row = projected,
                            encounterOrder = (sourceIndex.toLong() shl DIRECT_ORDER_SOURCE_SHIFT) + localOrder++,
                            sortValues = orderBy.items.map { item ->
                                val expression = item.expression
                                if (expression is CypherExpr.Variable) {
                                    orderContext[expression.name]
                                } else {
                                    localEvaluator.evaluate(expression, orderContext)
                                }
                            }
                        )
                        addOrderedTopRow(localTopRows, ranked, retainedCount, localComparator)
                    }
                    localTopRows.toList()
                }
            })
            localRows.forEach { rows ->
                rows.forEach { ranked -> addOrderedTopRow(topRows, ranked, retainedCount, comparator) }
            }
            waveStart = waveEnd
        }
        checkCancelled()
        return CypherResult(
            columns,
            topRows.toList().sortedWith(comparator).drop(skipCount).map(RankedProjectedRow::row)
        )
    }

    private fun addOrderedTopRow(
        rows: PriorityQueue<RankedProjectedRow>,
        row: RankedProjectedRow,
        limit: Int,
        comparator: Comparator<RankedProjectedRow>
    ) {
        if (rows.size < limit) {
            rows.add(row)
        } else if (comparator.compare(row, rows.peek()) < 0) {
            rows.poll()
            rows.add(row)
        }
    }

    private fun projectDistinctFilteredRows(
        matches: Sequence<Map<String, Any?>>,
        where: CypherClause.Where,
        ret: CypherClause.Return,
        columns: List<String>,
        skipCount: Int,
        retainedCount: Int
    ): CypherResult {
        val distinctRows = LinkedHashMap<Any, MutableMap<String, Any?>>()
        for (bindings in matches) {
            if (evaluator.evaluate(where.condition, bindings) != true) continue
            val projected = projectRow(ret.items, columns, bindings)
            addCypherDistinctRow(distinctRows, projected, retainedCount)
            if (!qualified && distinctRows.size >= retainedCount) break
        }
        return CypherResult(columns, distinctRows.values.drop(skipCount))
    }

    @Suppress("UNCHECKED_CAST")
    private fun projectOrderedFilteredRows(
        matches: Sequence<Map<String, Any?>>,
        where: CypherClause.Where,
        ret: CypherClause.Return,
        orderBy: CypherClause.OrderBy,
        columns: List<String>,
        skipCount: Int,
        retainedCount: Int
    ): CypherResult {
        val comparator = rankedRowComparator(orderBy)
        val topRows = PriorityQueue<RankedProjectedRow>(comparator.reversed())
        val selectedDistinctRows = if (ret.distinct) {
            HashMap<Any, RankedProjectedRow>()
        } else {
            null
        }
        var encounterOrder = 0L

        for (bindings in matches) {
            if (evaluator.evaluate(where.condition, bindings) != true) continue
            val projected = projectRow(ret.items, columns, bindings)
            val visible = selectedDistinctRows?.let {
                cypherValueKey(projected.filterKeys { key -> key != INTERNAL_PROVENANCE_KEY })
            }
            val existing = visible?.let { selectedDistinctRows?.get(it) }
            if (existing != null) {
                val graphIds = (existing.row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                    (projected[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
                if (graphIds.isNotEmpty()) existing.row[INTERNAL_PROVENANCE_KEY] = graphIds
                encounterOrder++
                continue
            }

            val ranked = RankedProjectedRow(
                row = projected,
                encounterOrder = encounterOrder++,
                sortValues = evaluateOrderValues(orderBy, bindings, projected)
            )
            if (topRows.size < retainedCount) {
                topRows.add(ranked)
                if (visible != null) selectedDistinctRows?.put(visible, ranked)
            } else if (comparator.compare(ranked, topRows.peek()) < 0) {
                val removed = topRows.poll()
                selectedDistinctRows?.remove(
                    cypherValueKey(removed.row.filterKeys { it != INTERNAL_PROVENANCE_KEY })
                )
                topRows.add(ranked)
                if (visible != null) selectedDistinctRows?.put(visible, ranked)
            }
        }
        checkCancelled()

        val rows = topRows.toList()
            .sortedWith(comparator)
            .drop(skipCount)
            .map(RankedProjectedRow::row)
        return CypherResult(columns, rows)
    }

    private fun rankedRowComparator(orderBy: CypherClause.OrderBy): Comparator<RankedProjectedRow> {
        var comparisons = 0
        return Comparator { left, right ->
            if (workTrackingEnabled) pollCancellation(comparisons++)
            for ((index, item) in orderBy.items.withIndex()) {
                val leftValue = left.sortValues[index]
                val rightValue = right.sortValues[index]
                val comparison = compareOrderValues(leftValue, rightValue, item.ascending)
                if (comparison != 0) return@Comparator comparison
            }
            left.encounterOrder.compareTo(right.encounterOrder)
        }
    }

    private fun evaluateOrderValues(
        orderBy: CypherClause.OrderBy,
        bindings: Map<String, Any?>,
        projected: Map<String, Any?>
    ): List<Any?> {
        val orderContext = bindings.toMutableMap().apply { putAll(projected) }
        return orderBy.items.map { item -> evaluateOrderValue(item.expression, orderContext) }
    }

    /**
     * Fast path for:
     *
     *   MATCH (a:Source)-[:TYPE]->(b:Target) RETURN ... LIMIT k
     */
    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
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

        val sourceClass = resolveNodeClass(sourcePattern.labels) ?: return CypherResult(columns, emptyList())
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
                if (rel.variable != null) {
                    bindings[rel.variable] = edge.value
                    addProvenance(bindings, edge.value)
                }
                rows.add(projectRow(ret.items, columns, bindings))
                if (rows.size >= limitCount) return CypherResult(columns, rows)
            }
        }

        return CypherResult(columns, rows)
    }

    private fun graphIdEquality(expression: CypherExpr, variable: String): String? = when (expression) {
        is CypherExpr.And -> graphIdEquality(expression.left, variable)
            ?: graphIdEquality(expression.right, variable)
        is CypherExpr.Comparison -> if (expression.op == "=") {
            when {
                isGraphIdReference(expression.left, variable) ->
                    graphIdValue(expression.right)
                isGraphIdReference(expression.right, variable) ->
                    graphIdValue(expression.left)
                else -> null
            }
        } else null
        else -> null
    }

    private fun stripConjunctiveGraphIdEquality(
        expression: CypherExpr,
        variable: String,
        graphId: String
    ): CypherExpr? = when (expression) {
        is CypherExpr.And -> {
            val left = stripConjunctiveGraphIdEquality(expression.left, variable, graphId)
            val right = stripConjunctiveGraphIdEquality(expression.right, variable, graphId)
            when {
                left == null -> right
                right == null -> left
                else -> CypherExpr.And(left, right)
            }
        }
        is CypherExpr.Comparison -> expression.takeUnless { comparison ->
            comparison.op == "=" && when {
                isGraphIdReference(comparison.left, variable) -> graphIdValue(comparison.right) == graphId
                isGraphIdReference(comparison.right, variable) -> graphIdValue(comparison.left) == graphId
                else -> false
            }
        }
        else -> expression
    }

    private fun graphIdEqualities(expression: CypherExpr, variables: Set<String>): Set<String> = when (expression) {
        is CypherExpr.And -> graphIdEqualities(expression.left, variables) +
            graphIdEqualities(expression.right, variables)
        is CypherExpr.Comparison -> if (expression.op == "=") {
            variables.firstNotNullOfOrNull { variable ->
                when {
                    isGraphIdReference(expression.left, variable) -> graphIdValue(expression.right)
                    isGraphIdReference(expression.right, variable) -> graphIdValue(expression.left)
                    else -> null
                }
            }?.let(::setOf).orEmpty()
        } else {
            emptySet()
        }
        else -> emptySet()
    }

    private fun graphIdValue(expression: CypherExpr): String? = when (expression) {
        is CypherExpr.Literal -> expression.value as? String
        is CypherExpr.Parameter -> activeParameters.get().orEmpty()[expression.name] as? String
        else -> null
    }

    private fun isGraphIdReference(expression: CypherExpr, variable: String): Boolean = when (expression) {
        is CypherExpr.Property -> expression.expression == CypherExpr.Variable(variable) &&
            expression.propertyName == GRAPH_ID_PROPERTY
        is CypherExpr.FunctionCall -> expression.name.equals("graphId", ignoreCase = true) &&
            !expression.distinct && expression.args.singleOrNull() == CypherExpr.Variable(variable)
        else -> false
    }

    private fun projectRow(
        items: List<ReturnItem>,
        columns: List<String>,
        bindings: Map<String, Any?>
    ): MutableMap<String, Any?> {
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
        var rows = if (patterns.size > 1) inputRows.map(::startRelationshipMatchState) else inputRows
        for (pattern in patterns) {
            val nextRows = mutableListOf<Map<String, Any?>>()
            for (inputRow in rows) {
                nextRows.addAll(matchPattern(pattern, inputRow, limit))
                if (limit != null && nextRows.size >= limit) break
            }
            rows = if (limit != null && nextRows.size > limit) nextRows.subList(0, limit) else nextRows
        }
        return rows.map(::removeRelationshipMatchState)
    }

    private fun executeOptionalMatch(
        patterns: List<CypherPattern>,
        where: CypherExpr?,
        inputRows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        for (inputRow in inputRows) {
            var currentMatches = listOf(
                if (patterns.size > 1) startRelationshipMatchState(inputRow) else inputRow
            )

            for (pattern in patterns) {
                val nextMatches = mutableListOf<Map<String, Any?>>()
                for (row in currentMatches) {
                    nextMatches.addAll(matchPattern(pattern, row))
                }
                currentMatches = nextMatches
            }

            // WHERE belongs to the OPTIONAL MATCH operation. A row for which
            // the pattern matched but the predicate did not is therefore an
            // unsuccessful optional match and must be preserved with only the
            // newly introduced variables set to null.
            if (where != null) {
                currentMatches = currentMatches.filter { evaluator.evaluate(where, it) == true }
            }

            if (currentMatches.isEmpty()) {
                // Optional match: null only NEW variables introduced by this pattern,
                // not variables already bound in inputRow
                val newVars = patterns.flatMap { it.variables() }
                    .filter { it !in inputRow }
                val nullBindings = newVars.associateWith { null as Any? }
                results.add(inputRow + nullBindings)
            } else {
                results.addAll(currentMatches.map(::removeRelationshipMatchState))
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
        val relationshipCount = elements.count { it is PatternElement.RelationshipPattern }
        val trackSegments = pattern.pathVariable != null || relationshipCount > 1
        val matches = if (limit != null) {
            matchPatternLazily(
                pattern,
                existingBindings,
                trackSegments = trackSegments
            ).take(limit).toList()
        } else {
            val initialBindings = prepareRelationshipMatchState(pattern, existingBindings)
            matchPatternEagerly(
                elements,
                initialBindings,
                limit = null,
                trackSegments = trackSegments
            )
        }

        if (pattern.pathVariable == null && matches.none(::containsInternalPathState)) return matches

        return matches.map { bindings ->
            val path = pattern.pathVariable?.let { buildPathRepresentation(pattern, bindings) }
            bindings.toMutableMap().apply {
                remove(INTERNAL_MATCHED_PATH_SEGMENTS_KEY)
                remove(INTERNAL_CURRENT_NODE_KEY)
                remove(INTERNAL_PATH_START_NODE_KEY)
                if (pattern.pathVariable != null) {
                    this[pattern.pathVariable] = path
                    addProvenance(this, path)
                }
            }
        }
    }

    private fun containsInternalPathState(bindings: Map<String, Any?>): Boolean =
        INTERNAL_MATCHED_PATH_SEGMENTS_KEY in bindings ||
            INTERNAL_CURRENT_NODE_KEY in bindings ||
            INTERNAL_PATH_START_NODE_KEY in bindings

    private fun prepareRelationshipMatchState(
        pattern: CypherPattern,
        bindings: Map<String, Any?>
    ): Map<String, Any?> {
        val relationshipCount = pattern.elements.count { it is PatternElement.RelationshipPattern }
        if (relationshipCount <= 1 && INTERNAL_RELATIONSHIP_MATCH_STATE_KEY !in bindings) return bindings
        val reserved = pattern.elements.asSequence()
            .filterIsInstance<PatternElement.RelationshipPattern>()
            .mapNotNull(PatternElement.RelationshipPattern::variable)
            .flatMap { variable -> relationshipBinding(bindings[variable]).asSequence() }
            .toSet()
        val previous = bindings[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] as? RelationshipMatchState
        return bindings.toMutableMap().apply {
            this[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] = RelationshipMatchState(
                reserved = reserved,
                used = previous?.used.orEmpty()
            )
        }
    }

    private fun startRelationshipMatchState(bindings: Map<String, Any?>): Map<String, Any?> =
        bindings.toMutableMap().apply {
            this[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] = RelationshipMatchState(emptySet(), emptySet())
        }

    private fun removeRelationshipMatchState(bindings: Map<String, Any?>): Map<String, Any?> {
        if (INTERNAL_RELATIONSHIP_MATCH_STATE_KEY !in bindings) return bindings
        return bindings.toMutableMap().apply { remove(INTERNAL_RELATIONSHIP_MATCH_STATE_KEY) }
    }

    @Suppress("ReturnCount")
    private fun matchPatternLazily(
        pattern: CypherPattern,
        existingBindings: Map<String, Any?>,
        candidateSources: List<CypherGraph> = sources,
        finalResultPredicate: ((Map<String, Any?>) -> Boolean)? = null,
        trackSegments: Boolean = pattern.pathVariable != null ||
            pattern.elements.count { it is PatternElement.RelationshipPattern } > 1
    ): Sequence<Map<String, Any?>> {
        val elements = pattern.elements
        if (elements.isEmpty()) return sequenceOf(existingBindings)
        val initialBindings = prepareRelationshipMatchState(pattern, existingBindings)

        var currentMatches: Sequence<Map<String, Any?>> = matchNodeElementLazily(
            elements[0] as PatternElement.NodePattern,
            initialBindings,
            candidateSources,
            navigate = elements.size > 1,
            trackSegments = trackSegments
        )
        var i = 1
        while (i < elements.size) {
            val sourceNode = elements[i - 1] as PatternElement.NodePattern
            val rel = elements[i] as PatternElement.RelationshipPattern
            val targetNode = elements[i + 1] as PatternElement.NodePattern
            val resultPredicate = finalResultPredicate.takeIf { i + 1 == elements.lastIndex }
            currentMatches = currentMatches.flatMap { bindings ->
                matchRelationshipLazily(sourceNode, rel, targetNode, bindings, resultPredicate)
            }
            i += 2
        }
        return currentMatches
    }

    private fun matchPatternEagerly(
        elements: List<PatternElement>,
        existingBindings: Map<String, Any?>,
        limit: Int?,
        trackSegments: Boolean
    ): List<Map<String, Any?>> {
        var currentMatches = matchNodeElement(
            elements[0] as PatternElement.NodePattern,
            existingBindings,
            limit,
            navigate = elements.size > 1,
            trackSegments = trackSegments
        )

        var i = 1
        while (i < elements.size) {
            val sourceNode = elements[i - 1] as PatternElement.NodePattern
            val rel = elements[i] as PatternElement.RelationshipPattern
            val targetNode = elements[i + 1] as PatternElement.NodePattern
            i += 2

            val nextMatches = mutableListOf<Map<String, Any?>>()
            for (bindings in currentMatches) {
                nextMatches.addAll(matchRelationship(sourceNode, rel, targetNode, bindings, limit = null))
            }
            currentMatches = nextMatches
        }

        return currentMatches
    }

    /**
     * Build a path representation as a list of alternating nodes and edges:
     * [startNode, edge1, node2, edge2, ..., endNode]
     *
     * Relationship matches are retained in an internal segment binding so
     * unnamed and variable-length relationships preserve their exact trail.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun buildPathRepresentation(
        pattern: CypherPattern,
        bindings: Map<String, Any?>
    ): Any {
        @Suppress("UNCHECKED_CAST")
        val matchedSegments = (bindings[INTERNAL_MATCHED_PATH_SEGMENTS_KEY] as? List<MatchedPathSegment>).orEmpty()
        val firstNode = (pattern.elements.firstOrNull() as? PatternElement.NodePattern)
            ?.variable
            ?.let(bindings::get)
            ?: bindings[INTERNAL_PATH_START_NODE_KEY]
        val path = buildList {
            if (firstNode != null) add(firstNode)
            matchedSegments.forEach { addAll(it.tail) }
        }
        return pathValue(path)
    }

    private fun pathValue(path: List<Any>): Any {
        if (!qualified) return PathFinder.Path(path.filterIsInstance<Node>(), path.filterIsInstance<Edge>())
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
        limit: Int? = null,
        navigate: Boolean,
        trackSegments: Boolean
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for (candidate in nodeElementCandidates(nodePattern, existingBindings)) {
            val bindings = bindNodeCandidate(
                candidate,
                nodePattern,
                existingBindings,
                navigate,
                trackSegments
            ) ?: continue
            results.add(bindings)
            if (limit != null && results.size >= limit) break
        }
        return results
    }

    private fun matchNodeElementLazily(
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>,
        candidateSources: List<CypherGraph> = sources,
        navigate: Boolean,
        trackSegments: Boolean
    ): Sequence<Map<String, Any?>> = nodeElementCandidates(nodePattern, existingBindings, candidateSources)
        .mapNotNull { candidate ->
            bindNodeCandidate(
                candidate,
                nodePattern,
                existingBindings,
                navigate = navigate,
                trackSegments = trackSegments
            )
        }

    private fun nodeElementCandidates(
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>,
        candidateSources: List<CypherGraph> = sources
    ): Sequence<Any> {
        val methodSource = nodePattern.labels.any(MethodQueryExecutor::isMethodLabel)
        val nodeClass = if (methodSource) Node::class.java else resolveNodeClass(nodePattern.labels)
            ?: return emptySequence()

        return if (nodePattern.variable != null &&
            existingBindings.containsKey(nodePattern.variable)
        ) {
            val existing = existingBindings[nodePattern.variable]
            val matchesSource = if (methodSource) {
                existing is MethodValue
            } else {
                nodeValue(existing)?.let(nodeClass::isInstance) == true
            }
            if (existing != null && matchesSource) {
                sequenceOf(existing)
            } else {
                emptySequence()
            }
        } else if (methodSource) {
            methodCandidates()
        } else {
            nodeCandidates(nodeClass, candidateSources)
        }
    }

    private fun bindNodeCandidate(
        candidate: Any,
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>,
        navigate: Boolean,
        trackSegments: Boolean
    ): Map<String, Any?>? {
        if (!matchesNodeConstraints(candidate, nodePattern, existingBindings)) return null
        return existingBindings.toMutableMap().apply {
            if (trackSegments) putIfAbsent(INTERNAL_PATH_START_NODE_KEY, candidate)
            if (navigate) this[INTERNAL_CURRENT_NODE_KEY] = candidate
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
        sourceNodePattern: PatternElement.NodePattern,
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>,
        limit: Int?
    ): List<Map<String, Any?>> {
        val matches = matchRelationshipLazily(sourceNodePattern, rel, targetNodePattern, bindings)
        return (limit?.let(matches::take) ?: matches).toList()
    }

    private fun matchRelationshipLazily(
        sourceNodePattern: PatternElement.NodePattern,
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>,
        resultPredicate: ((Map<String, Any?>) -> Boolean)? = null
    ): Sequence<Map<String, Any?>> {
        val sourceNode = sourceNodePattern.variable
            ?.let(bindings::get)
            ?.let(::nodeCursor)
            ?: findLastBoundNode(bindings)
            ?: return emptySequence()
        val resolvedEdgeTypes = rel.types.mapNotNull(NodePropertyAccessor::resolveEdgeType).distinct()
        val edgeClass = resolvedEdgeTypes.singleOrNull()
        return when {
            rel.types.isNotEmpty() && resolvedEdgeTypes.isEmpty() -> emptySequence()
            rel.variableLength ->
                matchVariableLengthPathLazily(
                    rel,
                    targetNodePattern,
                    sourceNode,
                    bindings,
                    edgeClass,
                    resultPredicate
                )
            else -> matchSingleHopLazily(rel, targetNodePattern, sourceNode, bindings, edgeClass)
        }
    }

    private fun matchSingleHopLazily(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        edgeClass: Class<out Edge>?
    ): Sequence<Map<String, Any?>> = sequence {
        val edges = edgesForDirection(sourceNode, rel.direction, edgeClass)
        val unavailableRelationships = unavailableRelationships(rel, bindings, sourceNode.source)

        for (edge in edges) {
            if (edge.edge in unavailableRelationships || !matchesRelationshipBinding(rel, edge.value, bindings)) continue
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
            addUsedRelationshipsIfTracked(newBindings, sourceNode.source, listOf(edge.edge))
            addMatchedPathSegmentIfTracked(
                newBindings,
                MatchedPathSegment(listOf(edge.value, targetValue), listOf(edge.edge))
            )
            yield(newBindings)
        }
    }

    private fun matchVariableLengthPathLazily(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        edgeClass: Class<out Edge>?,
        resultPredicate: ((Map<String, Any?>) -> Boolean)?
    ): Sequence<Map<String, Any?>> = sequence {
        val direction = when (rel.direction) {
            Direction.OUTGOING -> PathFinder.Direction.OUTGOING
            Direction.INCOMING -> PathFinder.Direction.INCOMING
            Direction.BOTH -> PathFinder.Direction.BOTH
        }

        val workTracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        val unavailableRelationships = unavailableRelationships(rel, bindings, sourceNode.source)
        val nodePredicate = variablePathNodePredicate(
            rel,
            targetNodePattern,
            sourceNode,
            bindings,
            resultPredicate
        )
        val paths = PathFinder.findPathMatches(
            graph = sourceNode.source.graph,
            sources = setOf(sourceNode.node.id),
            options = PathFinder.SearchOptions(
                targets = null,
                edgeType = edgeClass,
                minDepth = rel.minHops ?: 1,
                maxDepth = rel.maxHops,
                direction = direction,
                workTracker = workTracker,
                edgePredicate = { edge ->
                    edge !in unavailableRelationships && matchesRelConstraints(edge, rel, bindings)
                },
                nodePredicate = nodePredicate
            )
        )

        for (pathMatch in paths) {
            val endValue = nodeValue(sourceNode.source, pathMatch.endNode())
            val targetMatch = matchTargetNode(targetNodePattern, endValue, bindings) ?: continue

            val newBindings = targetMatch.toMutableMap()
            val mustMaterialize = mustMaterializePath(rel, bindings)
            if (mustMaterialize) {
                val path = pathMatch.materialize(workTracker)
                val relationshipValues = path.edges.map { edgeValue(sourceNode.source, it) }
                if (!matchesRelationshipBinding(rel, relationshipValues, bindings)) continue
                if (rel.variable != null) {
                    newBindings[rel.variable] = relationshipValues
                    addProvenance(newBindings, relationshipValues)
                }
                addUsedRelationshipsIfTracked(newBindings, sourceNode.source, path.edges)
                if (INTERNAL_PATH_START_NODE_KEY in bindings) {
                    val pathTail = buildList {
                        path.edges.indices.forEach { index ->
                            add(relationshipValues[index])
                            add(nodeValue(sourceNode.source, path.nodes[index + 1]))
                        }
                    }
                    addMatchedPathSegmentIfTracked(newBindings, MatchedPathSegment(pathTail, path.edges))
                }
            }
            yield(newBindings)
        }
    }

    private fun mustMaterializePath(
        rel: PatternElement.RelationshipPattern,
        bindings: Map<String, Any?>
    ): Boolean = rel.variable != null ||
        INTERNAL_PATH_START_NODE_KEY in bindings ||
        INTERNAL_RELATIONSHIP_MATCH_STATE_KEY in bindings

    private fun variablePathNodePredicate(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        resultPredicate: ((Map<String, Any?>) -> Boolean)?
    ): ((Node) -> Boolean)? {
        val targetNeedsFiltering = targetNodePattern.labels.isNotEmpty() ||
            targetNodePattern.properties.isNotEmpty() ||
            targetNodePattern.variable?.let(bindings::containsKey) == true
        val canPushResultPredicate = resultPredicate != null && rel.variable == null
        return if (targetNeedsFiltering || canPushResultPredicate) {
            { node: Node ->
                val endValue = nodeValue(sourceNode.source, node)
                val targetMatch = matchTargetNode(targetNodePattern, endValue, bindings)
                targetMatch != null &&
                    (!canPushResultPredicate || resultPredicate?.invoke(targetMatch) == true)
            }
        } else {
            null
        }
    }

    private fun unavailableRelationships(
        rel: PatternElement.RelationshipPattern,
        bindings: Map<String, Any?>,
        source: CypherGraph
    ): Set<Edge> {
        val state = bindings[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] as? RelationshipMatchState ?: return emptySet()
        val availableBinding = relationshipBinding(rel.variable?.let(bindings::get))
        return (state.used + (state.reserved - availableBinding))
            .asSequence()
            .filter { it.graphId == source.id }
            .mapTo(linkedSetOf()) { it.edge }
    }

    private fun addUsedRelationshipsIfTracked(
        bindings: MutableMap<String, Any?>,
        source: CypherGraph,
        edges: Collection<Edge>
    ) {
        val state = bindings[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] as? RelationshipMatchState ?: return
        val qualifiedEdges = edges.mapTo(linkedSetOf()) { QualifiedEdge(source.id, source.graph, it) }
        bindings[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] = state.copy(used = state.used + qualifiedEdges)
    }

    private fun matchesRelationshipBinding(
        rel: PatternElement.RelationshipPattern,
        candidate: Any,
        bindings: Map<String, Any?>
    ): Boolean = rel.variable == null || !bindings.containsKey(rel.variable) || bindings[rel.variable] == candidate

    private fun relationshipBinding(value: Any?): Set<QualifiedEdge> = when (value) {
        is Edge -> sources.singleOrNull()?.let { source ->
            setOf(QualifiedEdge(source.id, source.graph, value))
        }.orEmpty()
        is QualifiedEdge -> setOf(value)
        is PathFinder.Path -> sources.singleOrNull()?.let { source ->
            value.edges.mapTo(linkedSetOf()) { QualifiedEdge(source.id, source.graph, it) }
        }.orEmpty()
        is QualifiedPath -> value.edges.toSet()
        is List<*> -> value.flatMapTo(linkedSetOf(), ::relationshipBinding)
        else -> emptySet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun addMatchedPathSegment(bindings: MutableMap<String, Any?>, segment: MatchedPathSegment) {
        val segments = (bindings[INTERNAL_MATCHED_PATH_SEGMENTS_KEY] as? List<MatchedPathSegment>).orEmpty()
        bindings[INTERNAL_MATCHED_PATH_SEGMENTS_KEY] = segments + segment
    }

    private fun addMatchedPathSegmentIfTracked(
        bindings: MutableMap<String, Any?>,
        segment: MatchedPathSegment
    ) {
        if (INTERNAL_PATH_START_NODE_KEY in bindings) addMatchedPathSegment(bindings, segment)
    }

    @Suppress("ReturnCount")
    private fun matchTargetNode(
        targetPattern: PatternElement.NodePattern,
        value: Any,
        bindings: Map<String, Any?>
    ): Map<String, Any?>? {
        val node = nodeValue(value) ?: return null
        val nodeClass = resolveNodeClass(targetPattern.labels) ?: return null

        if (!nodeClass.isInstance(node)) return null

        // Check if already bound to a different node
        if (targetPattern.variable != null && bindings.containsKey(targetPattern.variable)) {
            val existing = bindings[targetPattern.variable]
            if (existing != value) return null
        }

        if (!matchesNodeConstraints(value, targetPattern, bindings)) return null

        val result = bindings.toMutableMap()
        result[INTERNAL_CURRENT_NODE_KEY] = value
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
        if (value is MethodValue) {
            if (pattern.labels.any { !MethodQueryExecutor.isMethodLabel(it) }) return false
            return pattern.properties.all { (key, expression) ->
                value.property(key) == evaluator.evaluate(expression, bindings)
            }
        }
        val node = nodeValue(value) ?: return false
        // Check all labels
        if (pattern.labels.size > 1) {
            val nodeLabels = CypherFunctions.labels(value).map { it.lowercase() }.toSet()
            val allMatch = pattern.labels.all { label ->
                val labelClass = NodePropertyAccessor.resolveNodeLabelOrNull(label)
                labelClass?.isInstance(node) == true || label.lowercase() in nodeLabels
            }
            if (!allMatch) return false
        }

        // Check inline property constraints
        return pattern.properties.all { (key, expression) ->
            val exprValue = evaluator.evaluate(expression, bindings)
            cypherEquals(nodeProperty(value, key), exprValue) == true
        }
    }

    private fun matchesRelConstraints(
        edge: Edge,
        rel: PatternElement.RelationshipPattern,
        bindings: Map<String, Any?>
    ): Boolean {
        if (rel.types.isNotEmpty()) {
            val typeMatches = rel.types.any { requested ->
                matchesRelationshipType(edge, requested)
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
            cypherEquals(edgeValue, exprValue) == true
        }
    }

    /**
     * Matches only the relationship spellings that are part of the Cypher surface.
     *
     * Keep the aliases explicit: removing every underscore made malformed names
     * such as `D_A_T_A_F_L_O_W` indistinguishable from `DATAFLOW` and allocated
     * normalized strings for every candidate edge.
     */
    private fun matchesRelationshipType(edge: Edge, requested: String): Boolean = when (edge) {
        is DataFlowEdge ->
            requested.equals("DATAFLOW", ignoreCase = true) ||
                requested.equals("DATA_FLOW", ignoreCase = true)
        is CallEdge -> requested.equals("CALL", ignoreCase = true)
        is TypeEdge -> requested.equals("TYPE", ignoreCase = true)
        is ControlFlowEdge ->
            requested.equals("CONTROL_FLOW", ignoreCase = true) ||
                requested.equals("CONTROLFLOW", ignoreCase = true)
        is ResourceEdge ->
            requested.equals("RESOURCE", ignoreCase = true) || when (edge.kind) {
                ResourceRelation.OPENS -> requested.equals("RESOURCE_OPEN", ignoreCase = true)
                ResourceRelation.LOADS -> requested.equals("RESOURCE_LOAD", ignoreCase = true)
                ResourceRelation.BUNDLE_CANDIDATE ->
                    requested.equals("RESOURCE_BUNDLE_CANDIDATE", ignoreCase = true)
                ResourceRelation.LOOKUP -> requested.equals("RESOURCE_LOOKUP", ignoreCase = true)
                ResourceRelation.ENUMERATES -> requested.equals("RESOURCE_KEYS", ignoreCase = true)
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

    private fun findLastBoundNode(bindings: Map<String, Any?>): NodeCursor? =
        nodeCursor(bindings[INTERNAL_CURRENT_NODE_KEY])

    private fun <T : Node> nodeCandidates(
        type: Class<T>,
        candidateSources: List<CypherGraph> = sources
    ): Sequence<Any> =
        if (qualified) {
            candidateSources.asSequence().flatMap { source ->
                trackWork(source.graph.nodes(type)).map { node -> QualifiedNode(source.id, source.graph, node) }
            }
        } else {
            trackWork(graph.nodes(type)).map { it as Any }
        }

    private fun methodCandidates(): Sequence<Any> {
        val tracker = if (workTrackingEnabled) activeWorkTracker.get() else null
        val cancellationConsumer = tracker?.let { activeTracker ->
            MethodMetadataScanConsumer { activeTracker.checkCancelled() }
        }
        return sources.asSequence().flatMap { source ->
            val methods = cancellationConsumer?.let { source.graph.methods(MethodPattern(), it) }
                ?: source.graph.methods(MethodPattern())
            methods.map { method -> MethodValue(source.id.takeIf { qualified }, method) }
        }
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
        val storageWorkConsumer = stringStorageWorkConsumer(sources.size, tracker)
        val workAware = graph.nodesByStringPropertyDisjunction(type, predicates, limit, storageWorkConsumer)
        return if (workAware != null || tracker != null) {
            workAware
        } else {
            graph.nodesByStringPropertyDisjunction(type, predicates, limit)
        }
    }

    private fun stringStorageWorkConsumer(
        sourceCount: Int,
        tracker: CypherWorkTracker?
    ): GraphWorkConsumer = if (sourceCount == 1) {
        tracker?.let { activeTracker -> ParallelGraphWorkBatchConsumer(activeTracker::consume) }
            ?: noOpParallelGraphWorkConsumer
    } else {
        tracker?.let { activeTracker -> SerialGraphWorkBatchConsumer(activeTracker::consume) }
            ?: noOpSerialGraphWorkConsumer
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
        is MethodValue -> value.property(property)
        is QualifiedNode -> when (property) {
            GRAPH_ID_PROPERTY -> value.graphId
            ELEMENT_ID_PROPERTY, QUALIFIED_ID_PROPERTY -> value.elementId
            else -> NodePropertyAccessor.getProperty(value.node, property)
        }
        is Node -> NodePropertyAccessor.getProperty(value, property)
        else -> null
    }

    private fun provenanceOf(value: Any?): Set<String> = when (value) {
        is MethodValue -> value.graphId?.let(::setOf).orEmpty()
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
        val normalizedValues = HashMap<Any, Map<String, Any?>>()
        for ((index, row) in rows.withIndex()) {
            pollCancellation(index)
            val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
            val exact = byVisibleValues[visible]
            val normalizedKey = if (exact == null && requiresCypherNormalization(visible)) {
                cypherValueKey(visible)
            } else {
                null
            }
            val representative = normalizedKey?.let(normalizedValues::get)
            val existing = exact ?: representative?.let(byVisibleValues::get)
            if (existing == null) {
                byVisibleValues[visible] = row.toMutableMap()
                if (normalizedKey != null) normalizedValues[normalizedKey] = visible
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
        val normalizedValues = HashMap<Any, Map<String, Any?>>()
        for (row in rows) {
            val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
            val exact = byVisibleValues[visible]
            val normalizedKey = if (exact == null && requiresCypherNormalization(visible)) {
                cypherValueKey(visible)
            } else {
                null
            }
            val representative = normalizedKey?.let(normalizedValues::get)
            val existing = exact ?: representative?.let(byVisibleValues::get)
            if (existing == null) {
                byVisibleValues[visible] = row.toMutableMap()
                if (normalizedKey != null) normalizedValues[normalizedKey] = visible
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

    @Suppress("NestedBlockDepth")
    private fun projectAndAggregate(
        items: List<ReturnItem>,
        rows: List<Map<String, Any?>>,
        distinct: Boolean,
        orderBy: CypherClause.OrderBy? = null
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
                    val groups = groupRowsByCypherValue(expandedItems, groupByIndices, rows, trackWork = false)
                    groups.map { groupRows ->
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
                    attachOrderValues(projected, row, distinct, orderBy)
                    projected
                }
            } else {
                projectTrackedRows(expandedItems, columns, rows, distinct, orderBy)
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
        val groups = groupRowsByCypherValue(items, groupByIndices, rows, trackWork = true)
        return groups.mapIndexed { index, groupRows ->
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

    private fun groupRowsByCypherValue(
        items: List<ReturnItem>,
        groupByIndices: List<Int>,
        rows: List<Map<String, Any?>>,
        trackWork: Boolean
    ): List<List<Map<String, Any?>>> {
        if (groupByIndices.size == 1) {
            return groupRowsBySingleCypherValue(items[groupByIndices.single()], rows, trackWork)
        }
        val exactGroups = LinkedHashMap<List<Any?>, MutableList<Map<String, Any?>>>()
        val normalizedGroups = HashMap<Any, List<Any?>>()
        for ((rowIndex, row) in rows.withIndex()) {
            if (trackWork) pollCancellation(rowIndex)
            val exactKey = groupByIndices.map { index -> evaluator.evaluate(items[index].expression, row) }
            val exactGroup = exactGroups[exactKey]
            val normalizedKey = if (exactGroup == null && requiresCypherNormalization(exactKey)) {
                cypherValueKey(exactKey)
            } else {
                null
            }
            val representative = normalizedKey?.let(normalizedGroups::get)
            val group = exactGroup ?: representative?.let(exactGroups::get)
            if (group != null) {
                group.add(row)
            } else {
                exactGroups[exactKey] = mutableListOf(row)
                if (normalizedKey != null) normalizedGroups[normalizedKey] = exactKey
            }
        }
        return exactGroups.values.toList()
    }

    private fun groupRowsBySingleCypherValue(
        item: ReturnItem,
        rows: List<Map<String, Any?>>,
        trackWork: Boolean
    ): List<List<Map<String, Any?>>> {
        val exactGroups = LinkedHashMap<Any?, MutableList<Map<String, Any?>>>()
        val normalizedGroups = HashMap<Any, Any?>()
        for ((rowIndex, row) in rows.withIndex()) {
            if (trackWork) pollCancellation(rowIndex)
            val exactKey = evaluator.evaluate(item.expression, row)
            val exactGroup = exactGroups[exactKey]
            val normalizedKey = if (exactGroup == null && requiresCypherNormalization(exactKey)) {
                cypherValueKey(exactKey)
            } else {
                null
            }
            val representative = normalizedKey?.let(normalizedGroups::get)
            val group = exactGroup ?: representative?.let(exactGroups::get)
            if (group != null) {
                group.add(row)
            } else {
                exactGroups[exactKey] = mutableListOf(row)
                if (normalizedKey != null) normalizedGroups[normalizedKey] = exactKey
            }
        }
        return exactGroups.values.toList()
    }

    private fun projectTrackedRows(
        items: List<ReturnItem>,
        columns: List<String>,
        rows: List<Map<String, Any?>>,
        distinct: Boolean,
        orderBy: CypherClause.OrderBy?
    ): List<Map<String, Any?>> = rows.mapIndexed { rowIndex, row ->
        pollCancellation(rowIndex)
        val projected = mutableMapOf<String, Any?>()
        for (i in items.indices) {
            projected[columns[i]] = evaluator.evaluate(items[i].expression, row)
        }
        copyProvenance(projected, listOf(row))
        attachOrderValues(projected, row, distinct, orderBy)
        projected
    }

    private fun attachOrderValues(
        projected: MutableMap<String, Any?>,
        source: Map<String, Any?>,
        distinct: Boolean,
        orderBy: CypherClause.OrderBy?
    ) {
        if (!distinct && orderBy != null && requiresPreProjectionOrderValues(orderBy, projected.keys)) {
            projected[INTERNAL_ORDER_VALUES_KEY] = evaluateOrderValues(orderBy, source, projected)
        }
    }

    private fun requiresPreProjectionOrderValues(
        orderBy: CypherClause.OrderBy,
        projectedColumns: Set<String>
    ): Boolean = orderBy.items.any { item ->
        val variable = item.expression as? CypherExpr.Variable
        variable == null || variable.name !in projectedColumns
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
        if (!workTrackingEnabled) return values.distinctBy(::cypherValueKey)
        val seen = LinkedHashSet<Any>()
        val distinct = ArrayList<Any?>()
        for ((index, value) in values.withIndex()) {
            pollCancellation(index)
            if (seen.add(cypherValueKey(value))) distinct.add(value)
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
        val sorted = if (!workTrackingEnabled) {
            rows.sortedWith(Comparator { left, right ->
                for ((itemIndex, item) in clause.items.withIndex()) {
                    val leftValue = orderValue(left, item, itemIndex)
                    val rightValue = orderValue(right, item, itemIndex)
                    val comparison = compareOrderValues(leftValue, rightValue, item.ascending)
                    if (comparison != 0) return@Comparator comparison
                }
                0
            })
        } else {
            var comparisons = 0
            rows.sortedWith(Comparator { left, right ->
                pollCancellation(comparisons++)
                for ((itemIndex, item) in clause.items.withIndex()) {
                    val leftValue = orderValue(left, item, itemIndex)
                    val rightValue = orderValue(right, item, itemIndex)
                    val comparison = compareOrderValues(leftValue, rightValue, item.ascending)
                    if (comparison != 0) return@Comparator comparison
                }
                0
            })
        }
        return sorted.map(::removeOrderValues)
    }

    @Suppress("UNCHECKED_CAST")
    private fun orderValue(row: Map<String, Any?>, item: SortItem, index: Int): Any? {
        val orderValues = row[INTERNAL_ORDER_VALUES_KEY] as? List<Any?>
        return if (orderValues != null) orderValues[index] else evaluateOrderValue(item.expression, row)
    }

    private fun removeOrderValues(row: Map<String, Any?>): Map<String, Any?> {
        if (INTERNAL_ORDER_VALUES_KEY !in row) return row
        return row.toMutableMap().apply { remove(INTERNAL_ORDER_VALUES_KEY) }
    }

    private fun evaluateOrderValue(expression: CypherExpr, row: Map<String, Any?>): Any? =
        if (expression is CypherExpr.Variable) row[expression.name] else evaluator.evaluate(expression, row)

    private fun pollCancellation(index: Int) {
        if ((index and CANCELLATION_POLL_MASK) == 0) checkCancelled()
    }

    private fun checkCancelled() {
        if (workTrackingEnabled) activeWorkTracker.get()?.checkCancelled()
    }

    /**
     * Cypher has a total ordering for ORDER BY that is wider than the set of
     * values accepted by comparison expressions. Null is always greatest, so
     * it is last for ASC and first for DESC. The remaining cross-type order is
     * map, node, relationship, list, path, string, boolean, number.
     */
    private fun compareOrderValues(a: Any?, b: Any?, ascending: Boolean): Int {
        val comparison = when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> compareNonNullOrderValues(a, b)
        }
        return if (ascending) comparison else -comparison
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun compareNonNullOrderValues(a: Any, b: Any): Int {
        when {
            a is Int && b is Int -> return a.compareTo(b)
            a is Long && b is Long -> return a.compareTo(b)
            a is Double && b is Double -> return compareFloatingPoint(a, b)
            a is Float && b is Float -> return compareFloatingPoint(a.toDouble(), b.toDouble())
            a is Short && b is Short -> return a.compareTo(b)
            a is Byte && b is Byte -> return a.compareTo(b)
            a is String && b is String -> return a.compareTo(b)
            a is Boolean && b is Boolean -> return a.compareTo(b)
            a is Number && b is Number -> return compareNumbers(a, b)
        }
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

    @Suppress("ReturnCount")
    private fun compareMaps(a: Map<*, *>, b: Map<*, *>): Int {
        val sizeComparison = a.size.compareTo(b.size)
        if (sizeComparison != 0) return sizeComparison
        val aKeys = a.keys.map { it.toString() }.sorted()
        val bKeys = b.keys.map { it.toString() }.sorted()
        val keyComparison = compareLists(aKeys, bKeys)
        if (keyComparison != 0) return keyComparison
        for (key in aKeys) {
            val valueComparison = compareOrderValues(a[key], b[key], ascending = true)
            if (valueComparison != 0) return valueComparison
        }
        return 0
    }

    private fun compareLists(a: List<*>, b: List<*>): Int {
        val commonSize = minOf(a.size, b.size)
        for (index in 0 until commonSize) {
            val comparison = compareOrderValues(a[index], b[index], ascending = true)
            if (comparison != 0) return comparison
        }
        return a.size.compareTo(b.size)
    }

    @Suppress("ReturnCount")
    private fun compareNumbers(a: Number, b: Number): Int = compareCypherNumbers(a, b)

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

    @Suppress("ReturnCount")
    private fun compareRelationships(a: Any, b: Any): Int {
        val graphComparison = orderGraphId(a).compareTo(orderGraphId(b))
        if (graphComparison != 0) return graphComparison
        val aEdge = requireNotNull(edgeValue(a))
        val bEdge = requireNotNull(edgeValue(b))
        val fromComparison = aEdge.from.value.compareTo(bEdge.from.value)
        if (fromComparison != 0) return fromComparison
        val toComparison = aEdge.to.value.compareTo(bEdge.to.value)
        if (toComparison != 0) return toComparison
        return aEdge::class.java.name.compareTo(bEdge::class.java.name)
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
        val match = clauses[matchIndex] as CypherClause.Match
        if (match.patterns.size != 1 || match.where != null) return null

        val limitIndex = clauses.indexOfFirst { it is CypherClause.Limit }
        if (limitIndex <= matchIndex) return null

        // Check that nothing between MATCH and LIMIT invalidates pushdown
        val between = clauses.subList(matchIndex + 1, limitIndex)
        val safe = between.all { clause ->
            when (clause) {
                is CypherClause.Return -> !clause.items.any { containsAggregation(it.expression) } && !clause.distinct
                is CypherClause.Where,
                is CypherClause.Match,
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
