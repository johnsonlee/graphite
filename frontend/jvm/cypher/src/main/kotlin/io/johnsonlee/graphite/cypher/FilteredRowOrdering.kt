package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.checkThreadInterrupted
import java.util.PriorityQueue

private const val DIRECT_ORDER_SOURCE_SHIFT = 56

internal class FilteredRowOrdering(
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations,
    private val rowProjection: CypherProjection,
    private val stringScanner: DirectStringScanner,
    private val stringScanPolicy: StringScanPolicy
) {
    private val qualified get() = graphAccess.qualified

    fun projectOrderedDirectStringRows(
        nodePattern: PatternElement.NodePattern,
        candidateSources: List<CypherGraph>,
        plan: FilteredRowPlan
    ): CypherResult? {
        if (plan.returned.distinct || nodePattern.labels.size > 1 || nodePattern.properties.isNotEmpty()) return null
        return nodePattern.variable?.let { variable ->
            val nodeClass = resolveNodeClass(nodePattern.labels)
            if (nodeClass == null) CypherResult(plan.columns, emptyList())
            else compileStringScan(nodeClass, variable, plan, candidateSources)?.execute()
        }
    }

    private fun compileStringScan(
        nodeClass: Class<out Node>,
        variable: String,
        plan: FilteredRowPlan,
        candidates: List<CypherGraph>
    ): OrderedStringScan? {
        val parameters = context.parameters
        val candidatePlan = DirectStringCandidatePlan.compile(plan.where.condition, variable, parameters) ?: return null
        val parallelism = directStringGraphParallelism(candidates.size)
        val multipleWorkers = candidates.size > 1 && parallelism > 1
        return if (multipleWorkers && !DirectStringTasks.isWorker &&
            stringScanPolicy.shouldParallelizeStringScan(nodeClass, candidatePlan.candidates)
        ) OrderedStringScan(nodeClass, variable, candidatePlan, plan, candidates, parameters, parallelism) else null
    }

    private inner class OrderedStringScan(
        private val nodeClass: Class<out Node>,
        private val variable: String,
        private val candidates: DirectStringCandidatePlan,
        private val plan: FilteredRowPlan,
        private val sources: List<CypherGraph>,
        private val parameters: Map<String, Any?>,
        private val parallelism: Int
    ) {
        private val orderBy = checkNotNull(plan.orderBy)
        private val tracker = if (context.workTrackingEnabled) context.workTracker else null

        fun execute(): CypherResult {
            val comparator = rankedRowComparator(orderBy)
            val rows = PriorityQueue<RankedProjectedRow>(plan.retained, comparator.reversed())
            var start = 0
            while (start < sources.size) {
                val end = minOf(sources.size, start + parallelism)
                val tasks = (start until end).map { index -> { sourceRows(index) } }
                val localRows = DirectStringTasks.runDirectStringTasks(tasks, parallelism)
                localRows.forEach { batch -> batch.forEach { addOrderedTopRow(rows, it, plan.retained, comparator) } }
                start = end
            }
            context.checkCancelled()
            return CypherResult(plan.columns, rows.toList().sortedWith(comparator).drop(plan.skip).map(RankedProjectedRow::row))
        }

        private fun sourceRows(index: Int): List<RankedProjectedRow> {
            val source = sources[index]
            val evaluator = ExpressionEvaluator(parameterResolver = parameters::get, graph = source.graph,
                checkCancelled = { checkThreadInterrupted { CypherQueryCancelledException() } })
            val bindings = mutableMapOf<String, Any?>(variable to null)
            val comparator = rankedRowComparator(orderBy)
            val rows = PriorityQueue<RankedProjectedRow>(plan.retained, comparator.reversed())
            var ordinal = 0L
            for (node in stringScanner.directStringCandidates(source.graph, nodeClass, candidates.candidates, tracker)) {
                val candidate = graphAccess.nodeValue(source, node)
                bindings[variable] = candidate
                if (evaluator.evaluate(plan.where.condition, bindings) != true) continue
                val projected = projectSourceRow(candidate, bindings, evaluator)
                val orderContext = bindings.toMutableMap().apply { putAll(projected) }
                val ranked = RankedProjectedRow(projected, (index.toLong() shl DIRECT_ORDER_SOURCE_SHIFT) + ordinal++,
                    orderBy.items.map { evaluateSortValue(it.expression, orderContext, evaluator) })
                addOrderedTopRow(rows, ranked, plan.retained, comparator)
            }
            return rows.toList()
        }

        private fun projectSourceRow(
            candidate: Any,
            bindings: Map<String, Any?>,
            evaluator: ExpressionEvaluator
        ): MutableMap<String, Any?> =
            mutableMapOf<String, Any?>().also { row ->
                plan.returned.items.indices.forEach { index ->
                    row[plan.columns[index]] = evaluator.evaluate(plan.returned.items[index].expression, bindings)
                }
                rowOperations.addProvenance(row, candidate)
            }

        private fun evaluateSortValue(expression: CypherExpr, bindings: Map<String, Any?>, evaluator: ExpressionEvaluator): Any? =
            if (expression is CypherExpr.Variable) bindings[expression.name] else evaluator.evaluate(expression, bindings)
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

    fun projectDistinctFilteredRows(
        matches: Sequence<Map<String, Any?>>,
        plan: FilteredRowPlan
    ): CypherResult {
        val where = plan.where
        val ret = plan.returned
        val columns = plan.columns
        val skipCount = plan.skip
        val retainedCount = plan.retained
        val distinctRows = LinkedHashMap<Any, MutableMap<String, Any?>>()
        for (bindings in matches) {
            if (context.evaluator.evaluate(where.condition, bindings) == true) {
                val projected = rowProjection.projectRow(ret.items, columns, bindings)
                CypherDistinctRows.addCypherDistinctRow(distinctRows, projected, retainedCount)
                if (!qualified && distinctRows.size >= retainedCount) break
            }
        }
        return CypherResult(columns, distinctRows.values.drop(skipCount))
    }

    fun projectOrderedFilteredRows(matches: Sequence<Map<String, Any?>>, plan: FilteredRowPlan): CypherResult {
        val collector = OrderedRowsCollector(plan)
        for (bindings in matches) {
            if (context.evaluator.evaluate(plan.where.condition, bindings) == true) collector.accept(bindings)
        }
        context.checkCancelled()
        return collector.result()
    }

    private inner class OrderedRowsCollector(private val plan: FilteredRowPlan) {
        private val orderBy = checkNotNull(plan.orderBy)
        private val comparator = rankedRowComparator(orderBy)
        private val rows = PriorityQueue<RankedProjectedRow>(comparator.reversed())
        private val distinct = if (plan.returned.distinct) HashMap<Any, RankedProjectedRow>() else null
        private var ordinal = 0L

        @Suppress("UNCHECKED_CAST")
        fun accept(bindings: Map<String, Any?>) {
            val projected = rowProjection.projectRow(plan.returned.items, plan.columns, bindings)
            val visible = distinct?.let { cypherValueKey(projected.filterKeys { key -> key != INTERNAL_PROVENANCE_KEY }) }
            val existing = visible?.let { distinct?.get(it) }
            if (existing != null) {
                val graphIds = (existing.row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                    (projected[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
                if (graphIds.isNotEmpty()) existing.row[INTERNAL_PROVENANCE_KEY] = graphIds
                ordinal++
            } else {
                admit(RankedProjectedRow(projected, ordinal++, rowOperations.evaluateOrderValues(orderBy, bindings, projected)), visible)
            }
        }

        private fun admit(ranked: RankedProjectedRow, visible: Any?) {
            if (rows.size < plan.retained) {
                rows.add(ranked)
                if (visible != null) distinct?.put(visible, ranked)
            } else if (comparator.compare(ranked, rows.peek()) < 0) {
                val removed = rows.poll()
                distinct?.remove(cypherValueKey(removed.row.filterKeys { it != INTERNAL_PROVENANCE_KEY }))
                rows.add(ranked)
                if (visible != null) distinct?.put(visible, ranked)
            }
        }

        fun result(): CypherResult = CypherResult(plan.columns,
            rows.toList().sortedWith(comparator).drop(plan.skip).map(RankedProjectedRow::row))
    }

    private fun rankedRowComparator(orderBy: CypherClause.OrderBy): Comparator<RankedProjectedRow> {
        var comparisons = 0
        return Comparator { left, right ->
            if (context.workTrackingEnabled) context.pollCancellation(comparisons++)
            for ((index, item) in orderBy.items.withIndex()) {
                val leftValue = left.sortValues[index]
                val rightValue = right.sortValues[index]
                val comparison = CypherOrdering.compare(leftValue, rightValue, item.ascending)
                if (comparison != 0) return@Comparator comparison
            }
            left.encounterOrder.compareTo(right.encounterOrder)
        }
    }
}
