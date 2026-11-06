package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StreamingStringPropertyProjection
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import io.johnsonlee.graphite.graph.WorkAwareStreamingStringPropertyProjection
import java.util.PriorityQueue

internal data class RankedProjectedRow(
    val row: MutableMap<String, Any?>,
    val encounterOrder: Long,
    val sortValues: List<Any?> = emptyList()
)

/** Ordered property and graph-id projection through bounded top-k collectors. */
internal class OrderedPropertyQueryExecutor(
    private val sources: List<CypherGraph>,
    private val qualified: Boolean,
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations
) {
    fun tryFastOrderedPropertyLimit(clauses: List<CypherClause>): CypherResult? =
        graphIdProjection(clauses)?.let(::executeGraphIdProjection)
            ?: OrderedPropertyLimitQuery.compile(clauses)?.let(::executePropertyProjection)

    private fun executePropertyProjection(query: OrderedPropertyLimitQuery): CypherResult {
        val storage = streamingProjection(query)
        val tracker = storage?.let { context.workTracker }
        val workConsumer = tracker.takeIf { context.workTrackingEnabled }
        val cancellation = storage?.let { { checkStreamingProjectionCancelled(tracker) } }
        cancellation?.invoke()
        if (query.limit <= 0) return CypherResult(query.columns, emptyList())
        val collector = ProjectionCollector(query, cancellation)
        val streamed = storage?.let { collector.stream(it, workConsumer, checkNotNull(cancellation)) } == true
        if (!streamed) {
            for (candidate in graphAccess.nodeCandidates(query.nodeClass)) {
                val row = linkedMapOf<String, Any?>()
                for (item in query.projections) row[item.column] = graphAccess.nodeProperty(candidate, item.property)
                val provenance = rowOperations.provenanceOf(candidate)
                if (provenance.isNotEmpty()) row[INTERNAL_PROVENANCE_KEY] = provenance
                collector.accept(row)
            }
        }
        context.checkCancelled()
        cancellation?.invoke()
        val result = collector.result()
        cancellation?.invoke()
        return result
    }

    private fun streamingProjection(query: OrderedPropertyLimitQuery): StreamingStringPropertyProjection? {
        val singleUnqualified = !qualified && sources.size == 1
        val directCallFields = query.nodeClass == CallSiteNode::class.java &&
            query.projections.all { it.property in CALL_SITE_DIRECT_STRING_PROPERTIES }
        return if (singleUnqualified && directCallFields) sources.single().graph as? StreamingStringPropertyProjection else null
    }

    private fun checkStreamingProjectionCancelled(tracker: CypherWorkTracker?) {
        tracker?.checkCancelled()
        checkThreadInterrupted { CypherQueryCancelledException("String projection interrupted") }
    }

    /** Owns comparison polling, encounter order and heap admission for both storage and node projections. */
    private inner class ProjectionCollector(
        private val query: OrderedPropertyLimitQuery,
        private val cancellation: (() -> Unit)?
    ) {
        private var comparisons = 0
        private var encounterOrder = 0L
        private val comparator = Comparator<RankedProjectedRow> { left, right -> compareRows(left, right) }
        private val rows = PriorityQueue(query.limit, comparator.reversed())

        private fun pollComparison() {
            if (cancellation != null) {
                if ((comparisons++ and CANCELLATION_POLL_MASK) == 0) cancellation.invoke()
            } else if (context.workTrackingEnabled) {
                context.pollCancellation(comparisons++)
            }
        }

        private fun compareRows(left: RankedProjectedRow, right: RankedProjectedRow): Int {
            pollComparison()
            for (sort in query.sortItems) {
                val comparison = CypherOrdering.compare(left.row[sort.column], right.row[sort.column], sort.ascending)
                if (comparison != 0) return comparison
            }
            return left.encounterOrder.compareTo(right.encounterOrder)
        }

        fun accept(row: MutableMap<String, Any?>) {
            val ranked = RankedProjectedRow(row, encounterOrder++)
            if (rows.size < query.limit) {
                rows.add(ranked)
            } else if (comparator.compare(ranked, rows.peek()) < 0) {
                rows.poll()
                rows.add(ranked)
            }
        }

        fun stream(storage: StreamingStringPropertyProjection, work: GraphWorkConsumer?, checkCancelled: () -> Unit): Boolean {
            val sortIndexes = IntArray(query.sortItems.size) { query.columns.indexOf(query.sortItems[it].column) }
            val consume: (StringPropertyProjectionRow) -> Unit = { projected -> acceptStorageRow(projected, sortIndexes, work) }
            val properties = query.projections.map(PropertyProjection::property)
            return if (storage is WorkAwareStreamingStringPropertyProjection) {
                storage.forEachStringPropertyProjection(query.nodeClass, properties, work, checkCancelled, consume)
            } else {
                storage.forEachStringPropertyProjection(query.nodeClass, properties, checkCancelled, consume)
            }
        }

        private fun acceptStorageRow(projected: StringPropertyProjectionRow, sortIndexes: IntArray, work: GraphWorkConsumer?) {
            work?.consume()
            val ordinal = encounterOrder++
            if (rows.size == query.limit && compareStorage(projected.values, ordinal, sortIndexes) >= 0) return
            val row = linkedMapOf<String, Any?>()
            query.projections.forEachIndexed { index, item -> row[item.column] = projected.values[index] }
            if (rows.size == query.limit) rows.poll()
            rows.add(RankedProjectedRow(row, ordinal))
        }

        private fun compareStorage(values: List<String?>, ordinal: Long, sortIndexes: IntArray): Int {
            pollComparison()
            val worst = rows.peek()
            for (index in query.sortItems.indices) {
                val sort = query.sortItems[index]
                val comparison = CypherOrdering.compare(values[sortIndexes[index]], worst.row[sort.column], sort.ascending)
                if (comparison != 0) return comparison
            }
            return ordinal.compareTo(worst.encounterOrder)
        }

        fun result(): CypherResult = CypherResult(query.columns, rows.toList().sortedWith(comparator).map(RankedProjectedRow::row))
    }

    private data class GraphIdProjection(val column: String, val ascending: Boolean, val limit: Int)

    private fun graphIdProjection(clauses: List<CypherClause>): GraphIdProjection? {
        if (!qualified) return null
        return orderedNodeProjection(clauses)?.takeIf(::isGraphIdShape)?.let { shape ->
            val item = shape.returned.items.single()
            val property = item.expression as? CypherExpr.Property
            val expectedOwner = CypherExpr.Variable(checkNotNull(shape.node.variable))
            val selectsGraphId = property?.expression == expectedOwner && property.propertyName == GRAPH_ID_PROPERTY
            val column = item.alias ?: item.expression.toCypherString()
            val sort = shape.orderBy.items.single()
            val limit = context.literalLimitCount(shape.limit.count)
            if (selectsGraphId && sort.expression == CypherExpr.Variable(column) && limit != null) {
                GraphIdProjection(column, sort.ascending, limit)
            } else {
                null
            }
        }
    }

    private fun isGraphIdShape(shape: OrderedNodeProjection): Boolean {
        val matchSupported = !shape.match.optional && shape.match.where == null
        val nodeSupported = shape.node.labels.isEmpty() && shape.node.properties.isEmpty() && shape.node.variable != null
        val outputSupported = shape.returned.distinct && shape.returned.items.size == 1 && shape.orderBy.items.size == 1
        return matchSupported && nodeSupported && outputSupported
    }

    private fun executeGraphIdProjection(query: GraphIdProjection): CypherResult {
        if (query.limit <= 0) return CypherResult(listOf(query.column), emptyList())
        val ordered = if (query.ascending) sources.sortedBy(CypherGraph::id) else sources.sortedByDescending(CypherGraph::id)
        val rows = ArrayList<Map<String, Any?>>(minOf(query.limit, sources.size))
        for (source in ordered) {
            context.checkCancelled()
            if (hasNode(source)) {
                rows += linkedMapOf<String, Any?>(query.column to source.id, INTERNAL_PROVENANCE_KEY to setOf(source.id))
                if (rows.size >= query.limit) break
            }
        }
        return CypherResult(listOf(query.column), rows)
    }

    private fun hasNode(source: CypherGraph): Boolean = source.graph.nodeCount(Node::class.java)?.let { it > 0L } ?: run {
        val candidates = graphAccess.trackWork(source.graph.nodes(Node::class.java)).iterator()
        candidates.hasNext() && candidates.next().let { true }
    }

    private data class OrderedPropertyLimitQuery(
        val nodeClass: Class<out Node>,
        val projections: List<PropertyProjection>,
        val sortItems: List<OrderedColumn>,
        val limit: Int
    ) {
        val columns: List<String> = projections.map(PropertyProjection::column)

        companion object {
            fun compile(clauses: List<CypherClause>): OrderedPropertyLimitQuery? = orderedNodeProjection(clauses)
                ?.takeIf { !it.match.optional && it.match.where == null }
                ?.let(::compile)

            private fun compile(shape: OrderedNodeProjection): OrderedPropertyLimitQuery? {
                if (shape.returned.distinct || shape.returned.items.isEmpty()) return null
                val node = shape.node
                return if (node.variable != null && node.labels.size <= 1 && node.properties.isEmpty()) compileOutput(shape) else null
            }

            private fun compileOutput(shape: OrderedNodeProjection): OrderedPropertyLimitQuery? {
                val projections = projections(shape.returned.items, checkNotNull(shape.node.variable)) ?: return null
                return sortItems(shape.orderBy, projections.map(PropertyProjection::column))?.let { sorts ->
                    val limit = ((shape.limit.count as? CypherExpr.Literal)?.value as? Number)?.toCypherInt()
                    val nodeClass = resolveNodeClass(shape.node.labels)
                    if (limit != null && limit <= MAX_ORDERED_TOP_K_ROWS && nodeClass != null) {
                        OrderedPropertyLimitQuery(nodeClass, projections, sorts, limit)
                    } else {
                        null
                    }
                }
            }

            private fun projections(items: List<ReturnItem>, variable: String): List<PropertyProjection>? =
                items.map { item ->
                    val property = (item.expression as? CypherExpr.Property)
                        ?.takeIf { it.expression == CypherExpr.Variable(variable) } ?: return null
                    PropertyProjection(property.propertyName, item.alias ?: item.expression.toCypherString())
                }.takeIf { projections -> projections.map(PropertyProjection::column).toSet().size == projections.size }

            private fun sortItems(orderBy: CypherClause.OrderBy, columns: List<String>): List<OrderedColumn>? =
                orderBy.items.map { item ->
                    val column = (item.expression as? CypherExpr.Variable)?.name?.takeIf { it in columns } ?: return null
                    OrderedColumn(column, item.ascending)
                }.takeIf { it.isNotEmpty() }
        }
    }

    private data class PropertyProjection(val property: String, val column: String)
    private data class OrderedColumn(val column: String, val ascending: Boolean)
}
