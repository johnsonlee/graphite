package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node

private const val COUNT_QUERY_CLAUSES = 2
private const val LABEL_HISTOGRAM_QUERY_CLAUSES = 5
private const val HISTOGRAM_ORDER_INDEX = 3
private const val HISTOGRAM_LIMIT_INDEX = 4

/** Count and label histogram plans answered solely by graph type metadata. */
internal class MetadataQueryExecutor(private val sources: List<CypherGraph>, private val qualified: Boolean) {
    fun tryFastNodeCount(clauses: List<CypherClause>): CypherResult? = NodeCountQuery.compile(clauses)?.let(::executeNodeCount)

    private fun executeNodeCount(query: NodeCountQuery): CypherResult? {
        var count = 0L
        val provenance = if (qualified) linkedSetOf<String>() else null
        for (source in sources) {
            val sourceCount = source.graph.nodeCount(query.nodeClass) ?: return null
            count += sourceCount
            if (sourceCount > 0) provenance?.add(source.id)
        }
        val row = mutableMapOf<String, Any?>(query.column to count)
        if (!provenance.isNullOrEmpty()) row[INTERNAL_PROVENANCE_KEY] = provenance
        return CypherResult(listOf(query.column), listOf(row))
    }

    fun tryFastLabelHistogram(clauses: List<CypherClause>): CypherResult? = LabelHistogramQuery.compile(clauses)?.let { query ->
        if (query.limit <= 0) CypherResult(query.columns, emptyList()) else executeHistogram(query)
    }

    private fun executeHistogram(query: LabelHistogramQuery): CypherResult? {
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
        val ordered = counts.entries.sortedWith(if (query.ascending) comparator else comparator.reversed())
        val rows = ordered.take(query.limit).map { (label, count) ->
            mutableMapOf<String, Any?>(query.labelColumn to label, query.countColumn to count).apply {
                provenance?.get(label)?.takeIf { it.isNotEmpty() }?.let { put(INTERNAL_PROVENANCE_KEY, it) }
            }
        }
        return CypherResult(query.columns, rows)
    }

    private data class NodeCountQuery(val nodeClass: Class<out Node>, val column: String) {
        companion object {
            fun compile(clauses: List<CypherClause>): NodeCountQuery? {
                if (clauses.size != COUNT_QUERY_CLAUSES) return null
                val match = clauses[0] as? CypherClause.Match
                val ret = clauses[1] as? CypherClause.Return
                return if (match != null && ret != null) compile(match, ret) else null
            }

            private fun compile(match: CypherClause.Match, ret: CypherClause.Return): NodeCountQuery? {
                if (match.optional || ret.distinct) return null
                val node = singleNodePattern(match)?.takeIf { it.labels.size <= 1 && it.properties.isEmpty() }
                val item = ret.items.singleOrNull()
                return if (node != null && item != null) compile(node, item) else null
            }

            private fun compile(node: PatternElement.NodePattern, item: ReturnItem): NodeCountQuery? {
                val variable = countedVariable(item.expression)
                if (variable == null || variable != "*" && variable != node.variable) return null
                return resolveNodeClass(node.labels)?.let { NodeCountQuery(it, item.alias ?: item.expression.toCypherString()) }
            }

            private fun countedVariable(expr: CypherExpr): String? = when (expr) {
                is CypherExpr.CountStar -> "*"
                is CypherExpr.FunctionCall -> {
                    val supported = expr.name.equals("count", ignoreCase = true) && !expr.distinct
                    if (supported) (expr.args.singleOrNull() as? CypherExpr.Variable)?.name else null
                }
                else -> null
            }
        }
    }

    private data class LabelHistogramQuery(
        val labelColumn: String,
        val countColumn: String,
        val ascending: Boolean,
        val limit: Int
    ) {
        val columns: List<String> = listOf(labelColumn, countColumn)

        companion object {
            fun compile(clauses: List<CypherClause>): LabelHistogramQuery? {
                if (clauses.size != LABEL_HISTOGRAM_QUERY_CLAUSES) return null
                val match = clauses[0] as? CypherClause.Match
                val unwind = clauses[1] as? CypherClause.Unwind
                val ret = clauses[2] as? CypherClause.Return
                val orderBy = clauses[HISTOGRAM_ORDER_INDEX] as? CypherClause.OrderBy
                val limit = clauses[HISTOGRAM_LIMIT_INDEX] as? CypherClause.Limit
                return when {
                    match == null || unwind == null || ret == null -> null
                    orderBy == null || limit == null -> null
                    else -> compile(match, unwind, ret, orderBy, limit)
                }
            }

            private fun compile(
                match: CypherClause.Match,
                unwind: CypherClause.Unwind,
                ret: CypherClause.Return,
                orderBy: CypherClause.OrderBy,
                limit: CypherClause.Limit
            ): LabelHistogramQuery? {
                if (match.optional || ret.distinct) return null
                val node = singleNodePattern(match)?.takeIf { it.labels.isEmpty() && it.properties.isEmpty() }
                val variable = node?.variable
                return if (variable != null && isLabelsCall(unwind.expression, variable)) {
                    compileProjection(unwind.variable, ret, orderBy, limit)
                } else {
                    null
                }
            }

            private fun isLabelsCall(expression: CypherExpr, variable: String): Boolean {
                val call = expression as? CypherExpr.FunctionCall ?: return false
                return call.name.equals("labels", ignoreCase = true) && !call.distinct &&
                    call.args.singleOrNull() == CypherExpr.Variable(variable)
            }

            private fun compileProjection(
                variable: String,
                ret: CypherClause.Return,
                orderBy: CypherClause.OrderBy,
                limit: CypherClause.Limit
            ): LabelHistogramQuery? {
                if (ret.items.size != 2 || ret.items[0].expression != CypherExpr.Variable(variable) ||
                    ret.items[1].expression != CypherExpr.CountStar
                ) return null
                val labelItem = ret.items[0]
                val countItem = ret.items[1]
                val labelColumn = labelItem.alias ?: labelItem.expression.toCypherString()
                val countColumn = countItem.alias ?: countItem.expression.toCypherString()
                val sort = orderBy.items.singleOrNull()
                val limitCount = (limit.count as? CypherExpr.Literal)?.value as? Number
                return if (sort?.expression == CypherExpr.Variable(countColumn) && limitCount != null) {
                    LabelHistogramQuery(labelColumn, countColumn, sort.ascending, limitCount.toCypherInt())
                } else {
                    null
                }
            }
        }
    }
}
