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
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.nodesByStringProperty

private const val COUNT_QUERY_CLAUSES = 2
private const val LABEL_HISTOGRAM_QUERY_CLAUSES = 5
private const val DISTINCT_LIMIT_QUERY_CLAUSES = 3
private const val FILTERED_LIMIT_QUERY_CLAUSES = 4
private const val SINGLE_HOP_LIMIT_QUERY_CLAUSES = 3
private const val SINGLE_HOP_PATTERN_ELEMENTS = 3
private const val SINGLE_GRAPH_ID = "single"
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
    private val qualified: Boolean
) {

    constructor(graph: Graph) : this(listOf(CypherGraph(SINGLE_GRAPH_ID, graph)), false)

    internal constructor(graphs: List<CypherGraph>) : this(graphs, true)

    init {
        require(sources.map { it.id }.distinct().size == sources.size) { "Graph ids must be unique" }
    }

    private val graph: Graph get() = sources.single().graph

    private val evaluator = ExpressionEvaluator()

    /**
     * Execute a list of clauses and return the final result.
     */
    fun execute(clauses: List<CypherClause>): CypherResult {
        val fastResult = tryFastNodeCount(clauses)
            ?: tryFastLabelHistogram(clauses)
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
                        rows = rows.filter { row ->
                            evaluator.evaluate(clause.where, row) == true
                        }
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
        }

        if (columns.isEmpty() && rows.isNotEmpty()) {
            columns = rows.first().keys.toList()
        }

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
            return sources.single().let { source -> source.graph.node(NodeId(nodeId))?.let { source to it } }
        }

        val separator = elementId.lastIndexOf(':')
        if (separator < 0 || separator == elementId.lastIndex) return null
        val graphId = elementId.substring(0, separator)
        val nodeId = elementId.substring(separator + 1).toIntOrNull() ?: return null
        val source = sources.firstOrNull { it.id == graphId } ?: return null
        return source.graph.node(NodeId(nodeId))?.let { source to it }
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
                return LabelHistogramQuery(labelColumn, countColumn, sort.ascending, limitCount.toInt())
            }
        }
    }

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
            val indexedNodes = source.graph.nodesByStringProperty(
                nodeClass,
                filter.property,
                filter.mode,
                filter.expected,
                limit - rows.size
            )
            val candidates = indexedNodes ?: source.graph.nodes(nodeClass).filter(filter::matches)
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

    private fun directStringCandidates(
        graph: Graph,
        nodeClass: Class<out Node>,
        disjunction: DirectStringDisjunction
    ): Sequence<Node> = sequence {
        for ((candidateType, properties) in DIRECT_STRING_NODE_PROPERTIES) {
            if (!nodeClass.isAssignableFrom(candidateType)) continue
            val filters = disjunction.filters.filter { it.property in properties }
            if (filters.isEmpty()) continue

            val completeScanLimit = graph.nodeCount(candidateType)
                ?.takeIf { it < Int.MAX_VALUE }
                ?.toInt()
                ?: Int.MAX_VALUE
            val accelerated = filters.map { filter ->
                graph.nodesByStringProperty(
                    candidateType,
                    filter.property,
                    filter.mode,
                    filter.expected,
                    completeScanLimit
                )
            }
            val candidates = if (accelerated.any { it == null }) {
                graph.nodes(candidateType).filter(disjunction::matches)
            } else {
                filterOwnedNodes(filters, accelerated.filterNotNull())
            }
            for (node in candidates) yield(node)
        }
    }

    private fun <T : Node> filterOwnedNodes(
        filters: List<DirectStringFilter>,
        sequences: List<Sequence<T>>
    ): Sequence<Node> = sequence {
        for ((index, nodes) in sequences.withIndex()) {
            for (node in nodes) {
                var ownedByEarlierFilter = false
                for (earlierIndex in 0 until index) {
                    if (filters[earlierIndex].matches(node)) {
                        ownedByEarlierFilter = true
                        break
                    }
                }
                if (!ownedByEarlierFilter) yield(node)
            }
        }
    }

    private data class DirectStringFilter(
        val property: String,
        val mode: StringMatchMode,
        val expected: String
    ) {
        fun matches(node: Node): Boolean {
            val actual = NodePropertyAccessor.getProperty(node, property) as? String ?: return false
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
                val property = stringOp.left as? CypherExpr.Property ?: return null
                val owner = property.expression as? CypherExpr.Variable ?: return null
                val literal = stringOp.right as? CypherExpr.Literal ?: return null
                val expected = literal.value as? String ?: return null
                if (owner.name != variable) return null
                if (property.propertyName in QUALIFIED_NODE_PROPERTIES) return null
                val mode = when (stringOp.op) {
                    "STARTS WITH" -> StringMatchMode.STARTS_WITH
                    "ENDS WITH" -> StringMatchMode.ENDS_WITH
                    "CONTAINS" -> StringMatchMode.CONTAINS
                    else -> return null
                }
                return DirectStringFilter(property.propertyName, mode, expected)
            }
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
                val target = source.source.graph.node(targetId) ?: continue
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

        // A pattern is a chain: Node [Rel Node [Rel Node ...]]
        // Start by matching the first node, then alternate rel+node.
        var currentMatches = matchNodeElement(elements[0] as PatternElement.NodePattern, existingBindings, limit)

        var i = 1
        while (i < elements.size) {
            val rel = elements[i] as PatternElement.RelationshipPattern
            val targetNode = elements[i + 1] as PatternElement.NodePattern
            i += 2

            val nextMatches = mutableListOf<Map<String, Any?>>()
            for (bindings in currentMatches) {
                nextMatches.addAll(matchRelationship(rel, targetNode, bindings))
                if (limit != null && nextMatches.size >= limit) break
            }
            currentMatches = if (limit != null && nextMatches.size > limit) {
                nextMatches.subList(0, limit)
            } else {
                nextMatches
            }
        }

        // If the pattern has a path variable, bind it to the matched path
        if (pattern.pathVariable != null) {
            return currentMatches.map { bindings ->
                val path = buildPathRepresentation(pattern, bindings)
                bindings.toMutableMap().apply {
                    this[pattern.pathVariable] = path
                    addProvenance(this, path)
                }
            }
        }

        return currentMatches
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
                            val foundEdge = prev.source.graph.outgoing(prev.node.id)
                                .firstOrNull { it.to == next.node.id }
                                ?: prev.source.graph.incoming(prev.node.id)
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

        // Determine the node class from the first label (if any)
        val nodeClass = nodePattern.labels.firstOrNull()
            ?.let { NodePropertyAccessor.resolveNodeLabel(it) }
            ?: Node::class.java

        val candidates: Sequence<Any> = if (nodePattern.variable != null &&
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

        for (candidate in candidates) {
            if (matchesNodeConstraints(candidate, nodePattern, existingBindings)) {
                val bindings = existingBindings.toMutableMap()
                if (nodePattern.variable != null) {
                    bindings[nodePattern.variable] = candidate
                    addProvenance(bindings, candidate)
                }
                results.add(bindings)
                if (limit != null && results.size >= limit) break
            }
        }

        return results
    }

    /**
     * Match a relationship + target node from the current source node binding.
     */
    private fun matchRelationship(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        // The source node is the last-bound node in the current bindings.
        val sourceNode = findLastBoundNode(bindings) ?: return results

        val edgeClass = rel.types.firstOrNull()?.let { NodePropertyAccessor.resolveEdgeType(it) }

        if (rel.variableLength) {
            matchVariableLengthPath(rel, targetNodePattern, sourceNode, bindings, edgeClass, results)
        } else {
            matchSingleHop(rel, targetNodePattern, sourceNode, bindings, edgeClass, results)
        }

        return results
    }

    private fun matchSingleHop(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        edgeClass: Class<out Edge>?,
        results: MutableList<Map<String, Any?>>
    ) {
        val edges = edgesForDirection(sourceNode, rel.direction, edgeClass)

        for (edge in edges) {
            val targetId = resolveTargetId(edge.edge, sourceNode.node.id, rel.direction)
            val targetNode = sourceNode.source.graph.node(targetId) ?: continue
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
        }
    }

    private fun matchVariableLengthPath(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        edgeClass: Class<out Edge>?,
        results: MutableList<Map<String, Any?>>
    ) {
        val direction = when (rel.direction) {
            Direction.OUTGOING -> PathFinder.Direction.OUTGOING
            Direction.INCOMING -> PathFinder.Direction.INCOMING
            Direction.BOTH -> PathFinder.Direction.BOTH
        }

        val paths = PathFinder.findPaths(
            graph = sourceNode.source.graph,
            sources = setOf(sourceNode.node.id),
            targets = null,
            edgeType = edgeClass,
            minDepth = rel.minHops ?: 1,
            maxDepth = rel.maxHops ?: 10,
            direction = direction
        )

        for (path in paths) {
            val endNode = path.nodes.last()
            val endValue = nodeValue(sourceNode.source, endNode)
            val targetMatch = matchTargetNode(targetNodePattern, endValue, bindings) ?: continue

            val newBindings = targetMatch.toMutableMap()
            if (rel.variable != null) {
                val pathValue = if (qualified) {
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
        val edges = when (direction) {
        Direction.OUTGOING ->
            if (edgeClass != null) graph.outgoing(nodeId, edgeClass) else graph.outgoing(nodeId)
        Direction.INCOMING ->
            if (edgeClass != null) graph.incoming(nodeId, edgeClass) else graph.incoming(nodeId)
        Direction.BOTH -> {
            val out = if (edgeClass != null) graph.outgoing(nodeId, edgeClass) else graph.outgoing(nodeId)
            val inc = if (edgeClass != null) graph.incoming(nodeId, edgeClass) else graph.incoming(nodeId)
            out + inc
        }
        }
        return edges.map { edge -> EdgeCursor(node.source, edge, edgeValue(node.source, edge)) }
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
                source.graph.nodes(type).map { node -> QualifiedNode(source.id, source.graph, node) }
            }
        } else {
            graph.nodes(type).map { it as Any }
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
        return rows.filter { row ->
            evaluator.evaluate(clause.condition, row) == true
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
                val groups = rows.groupBy { row ->
                    groupByIndices.map { i -> evaluator.evaluate(expandedItems[i].expression, row) }
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
            }
        } else {
            rows.map { row ->
                val projected = mutableMapOf<String, Any?>()
                for (i in expandedItems.indices) {
                    projected[columns[i]] = evaluator.evaluate(expandedItems[i].expression, row)
                }
                copyProvenance(projected, listOf(row))
                projected
            }
        }

        val finalRows = if (distinct) distinctByVisibleValues(resultRows) else resultRows
        return finalRows to columns
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
                val values = rows.map { row ->
                    if (expr.args.isEmpty()) row
                    else evaluator.evaluate(expr.args[0], row)
                }
                val filtered = if (expr.distinct) values.distinct() else values
                CypherFunctions.aggregate(expr.name, filtered)
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

    // ========================================================================
    // ORDER BY
    // ========================================================================

    private fun executeOrderBy(
        clause: CypherClause.OrderBy,
        rows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        return rows.sortedWith(Comparator { a, b ->
            for (item in clause.items) {
                val va = evaluator.evaluate(item.expression, a)
                val vb = evaluator.evaluate(item.expression, b)
                val cmp = compareNullable(va, vb)
                if (cmp != 0) return@Comparator if (item.ascending) cmp else -cmp
            }
            0
        })
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
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
    }
}

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
