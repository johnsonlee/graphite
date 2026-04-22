package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph

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
class QueryPipeline(private val graph: Graph) {

    private val evaluator = ExpressionEvaluator()

    /**
     * Execute a list of clauses and return the final result.
     */
    fun execute(clauses: List<CypherClause>): CypherResult {
        var rows: List<Map<String, Any?>> = listOf(emptyMap())
        var columns: List<String> = emptyList()

        // Pre-compute early limit: if clauses between MATCH and LIMIT contain
        // no WHERE, WITH, ORDER BY, UNWIND, or aggregation, we can stop the
        // node scan early instead of materialising all candidates first.
        val earlyLimit = computeEarlyLimit(clauses)

        for (clause in clauses) {
            when (clause) {
                is CypherClause.Match -> {
                    rows = if (clause.optional) {
                        executeOptionalMatch(clause.patterns, rows)
                    } else {
                        executeMatch(clause.patterns, rows, earlyLimit)
                    }
                }
                is CypherClause.Where -> rows = executeWhere(clause, rows)
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
                bindings + (pattern.pathVariable to path)
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
    private fun buildPathRepresentation(
        pattern: CypherPattern,
        bindings: Map<String, Any?>
    ): List<Any> {
        val path = mutableListOf<Any>()
        val elements = pattern.elements

        for (i in elements.indices) {
            when (val element = elements[i]) {
                is PatternElement.NodePattern -> {
                    val node = element.variable?.let { bindings[it] }
                    if (node is Node) path.add(node)
                }
                is PatternElement.RelationshipPattern -> {
                    val edge = element.variable?.let { bindings[it] }
                    if (edge is Edge) {
                        path.add(edge)
                    } else {
                        // Look up the edge between the previous and next nodes
                        val prevNode = elements.getOrNull(i - 1)
                            ?.let { it as? PatternElement.NodePattern }
                            ?.variable?.let { bindings[it] as? Node }
                        val nextNode = elements.getOrNull(i + 1)
                            ?.let { it as? PatternElement.NodePattern }
                            ?.variable?.let { bindings[it] as? Node }
                        if (prevNode != null && nextNode != null) {
                            val foundEdge = graph.outgoing(prevNode.id)
                                .firstOrNull { it.to == nextNode.id }
                                ?: graph.incoming(prevNode.id)
                                    .firstOrNull { it.from == nextNode.id }
                            if (foundEdge != null) path.add(foundEdge)
                        }
                    }
                }
            }
        }
        return path
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

        val candidates = if (nodePattern.variable != null &&
            existingBindings.containsKey(nodePattern.variable)
        ) {
            val existing = existingBindings[nodePattern.variable]
            if (existing is Node && nodeClass.isInstance(existing)) {
                sequenceOf(existing)
            } else {
                emptySequence()
            }
        } else {
            graph.nodes(nodeClass)
        }

        for (node in candidates) {
            if (matchesNodeConstraints(node, nodePattern, existingBindings)) {
                val bindings = existingBindings.toMutableMap()
                if (nodePattern.variable != null) bindings[nodePattern.variable] = node
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
        sourceNode: Node,
        bindings: Map<String, Any?>,
        edgeClass: Class<out Edge>?,
        results: MutableList<Map<String, Any?>>
    ) {
        val edges = edgesForDirection(sourceNode.id, rel.direction, edgeClass)

        for (edge in edges) {
            val targetId = resolveTargetId(edge, sourceNode.id, rel.direction)
            val targetNode = graph.node(targetId) ?: continue

            // Check relationship property constraints
            if (!matchesRelConstraints(edge, rel, bindings)) continue

            val targetMatch = matchTargetNode(targetNodePattern, targetNode, bindings) ?: continue

            val newBindings = targetMatch.toMutableMap()
            if (rel.variable != null) newBindings[rel.variable] = edge
            results.add(newBindings)
        }
    }

    private fun matchVariableLengthPath(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: Node,
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
            graph = graph,
            sources = setOf(sourceNode.id),
            targets = null,
            edgeType = edgeClass,
            minDepth = rel.minHops ?: 1,
            maxDepth = rel.maxHops ?: 10,
            direction = direction
        )

        for (path in paths) {
            val endNode = path.nodes.last()
            val targetMatch = matchTargetNode(targetNodePattern, endNode, bindings) ?: continue

            val newBindings = targetMatch.toMutableMap()
            if (rel.variable != null) newBindings[rel.variable] = path
            results.add(newBindings)
        }
    }

    private fun matchTargetNode(
        targetPattern: PatternElement.NodePattern,
        node: Node,
        bindings: Map<String, Any?>
    ): Map<String, Any?>? {
        val nodeClass = targetPattern.labels.firstOrNull()
            ?.let { NodePropertyAccessor.resolveNodeLabel(it) }
            ?: Node::class.java

        if (!nodeClass.isInstance(node)) return null

        // Check if already bound to a different node
        if (targetPattern.variable != null && bindings.containsKey(targetPattern.variable)) {
            val existing = bindings[targetPattern.variable]
            if (existing is Node && existing.id != node.id) return null
        }

        if (!matchesNodeConstraints(node, targetPattern, bindings)) return null

        val result = bindings.toMutableMap()
        if (targetPattern.variable != null) result[targetPattern.variable] = node
        return result
    }

    private fun matchesNodeConstraints(
        node: Node,
        pattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>
    ): Boolean {
        // Check all labels
        if (pattern.labels.size > 1) {
            val nodeLabels = CypherFunctions.labels(node).map { it.lowercase() }.toSet()
            val allMatch = pattern.labels.all { label ->
                val labelClass = NodePropertyAccessor.resolveNodeLabel(label)
                labelClass.isInstance(node) || label.lowercase() in nodeLabels
            }
            if (!allMatch) return false
        }

        // Check inline property constraints
        return pattern.properties.all { (key, value) ->
            val exprValue = evaluator.evaluate(value, bindings)
            NodePropertyAccessor.getProperty(node, key) == exprValue
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
        nodeId: NodeId,
        direction: Direction,
        edgeClass: Class<out Edge>?
    ): Sequence<Edge> = when (direction) {
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

    private fun resolveTargetId(edge: Edge, sourceId: NodeId, direction: Direction): NodeId =
        when (direction) {
            Direction.OUTGOING -> edge.to
            Direction.INCOMING -> edge.from
            Direction.BOTH -> if (edge.from == sourceId) edge.to else edge.from
        }

    private fun findLastBoundNode(bindings: Map<String, Any?>): Node? {
        // Return the last Node value in insertion order
        var last: Node? = null
        for (value in bindings.values) {
            if (value is Node) last = value
        }
        return last
    }

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
            val allKeys = rows.firstOrNull()?.keys ?: emptySet()
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
                    row
                }
            }
        } else {
            rows.map { row ->
                val projected = mutableMapOf<String, Any?>()
                for (i in expandedItems.indices) {
                    projected[columns[i]] = evaluator.evaluate(expandedItems[i].expression, row)
                }
                projected
            }
        }

        val finalRows = if (distinct) resultRows.distinct() else resultRows
        return finalRows to columns
    }

    private fun containsAggregation(expr: CypherExpr): Boolean = when (expr) {
        is CypherExpr.FunctionCall ->
            CypherFunctions.isAggregation(expr.name) || expr.args.any { containsAggregation(it) }
        is CypherExpr.CountStar -> true
        is CypherExpr.Property -> containsAggregation(expr.expression)
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
