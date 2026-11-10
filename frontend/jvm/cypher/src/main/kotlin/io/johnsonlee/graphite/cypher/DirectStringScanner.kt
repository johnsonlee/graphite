package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import java.util.PriorityQueue

/** Storage candidate merging, bounded scan cursors and selected-row membership. */
internal class DirectStringScanner(
    private val sources: List<CypherGraph>,
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess
) {
    inner class DirectStringSourceScanner(
        val source: CypherGraph,
        private val projection: StringProjectionSpec,
        private val tracker: CypherWorkTracker?,
        nodePredicateFactory: DirectNodePredicateFactory? = null,
        private val storage: StringStorageOptions = StringStorageOptions(sources.size)
    ) {
        private val nodeClass get() = projection.nodeClass
        private val filter get() = projection.filter
        private val items get() = projection.items
        private val columns get() = projection.columns
        private val nodePredicate = nodePredicateFactory?.invoke(source)
        private val localRows = HashSet<Map<String, Any?>>()
        private var iterator: Iterator<Node>? = null

        private fun candidateIterator(limit: Int): Iterator<Node> = iterator
            ?: directStringCandidates(
                                        graph = source.graph,
                                        nodeClass = nodeClass,
                                        disjunction = filter,
                                        tracker = tracker,
                                        limit = limit,
                                        storage = storage
                                    )
            .let { nodes -> nodePredicate?.let { predicate -> nodes.filter(predicate) } ?: nodes }
            .iterator()
            .also { iterator = it }

        private var exhausted = false
        private var inspected = 0

        fun nextDistinctRows(maxRows: Int): DirectStringScanBatch {
            val rows = mutableListOf<Map<String, Any?>>()
            if (maxRows <= 0) return DirectStringScanBatch(rows, exhausted)
            val candidates = candidateIterator(Int.MAX_VALUE)
            while (!exhausted && rows.size < maxRows) {
                if (!candidates.hasNext()) {
                    exhausted = true
                    break
                }
                val row = projectParallelSafeNode(candidates.next())
                val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
                if (localRows.add(visible)) rows += row
                pollInterrupted()
            }
            return DirectStringScanBatch(rows, exhausted)
        }

        fun nextRows(maxRows: Int): List<Map<String, Any?>> {
            val rows = mutableListOf<Map<String, Any?>>()
            if (maxRows <= 0) return rows
            // With no residual node predicate, every storage hit becomes exactly one output row.
            // Passing the requested batch size down lets bounded raw scans stop at LIMIT and makes
            // the storage raw-prefix strategy eligible. Residual predicates must retain an
            // unbounded candidate stream because rejected hits do not count toward the row limit.
            val storageLimit = if (nodePredicate == null) maxRows else Int.MAX_VALUE
            val candidates = candidateIterator(storageLimit)
            while (!exhausted && rows.size < maxRows) {
                if (!candidates.hasNext()) {
                    exhausted = true
                    break
                }
                rows += projectParallelSafeNode(candidates.next())
                pollInterrupted()
            }
            return rows
        }

        fun collectRemainingSelectedRows(
            selected: DirectStringSelectedRowMatcher
        ): Set<Map<String, Any?>> {
            val hits = mutableSetOf<Map<String, Any?>>()
            val matcher = selected.cursor(source)
            val candidates = candidateIterator(Int.MAX_VALUE)
            while (!exhausted) {
                if (!candidates.hasNext()) {
                    exhausted = true
                    break
                }
                matcher.match(candidates.next())?.let(hits::add)
                pollInterrupted()
            }
            return hits
        }

        private fun projectParallelSafeNode(node: Node): Map<String, Any?> {
            val candidate = graphAccess.nodeValue(source, node)
            return mutableMapOf<String, Any?>().apply {
                items.indices.forEach { index ->
                    this[columns[index]] = when (val expression = items[index].expression) {
                        is CypherExpr.Literal -> expression.value
                        is CypherExpr.Property -> graphAccess.nodeProperty(candidate, expression.propertyName)
                        else -> error("Unsafe expression reached parallel string projection")
                    }
                }
                put(INTERNAL_PROVENANCE_KEY, setOf(source.id))
            }
        }

        private fun pollInterrupted() {
            inspected++
            if ((inspected and CANCELLATION_POLL_MASK) == 0) {
                checkThreadInterrupted { CypherQueryCancelledException() }
            }
        }
    }

    data class DirectStringScanBatch(
        val rows: List<Map<String, Any?>>,
        val exhausted: Boolean
    )

    inner class DirectStringSelectedRowMatcher(
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
                val candidate = graphAccess.nodeValue(source, node)
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
                // Both admission paths accept only literals and direct properties.
                else -> graphAccess.nodeProperty(candidate, (expression as CypherExpr.Property).propertyName)
            }
        }
    }

    fun directStringCandidates(
        graph: Graph,
        nodeClass: Class<out Node>,
        disjunction: DirectStringDisjunction,
        tracker: CypherWorkTracker? = if (context.workTrackingEnabled) context.workTracker else null,
        excludedTypes: Set<Class<out Node>> = emptySet(),
        limit: Int = Int.MAX_VALUE,
        storage: StringStorageOptions = StringStorageOptions(sources.size)
    ): Sequence<Node> {
        if (limit <= 0) return emptySequence()
        return if (requiresPolymorphicOrder(graph, nodeClass, disjunction, excludedTypes)) {
            interruptible(graphAccess.trackWork(graph.nodes(nodeClass), tracker))
                .filter { node -> node.javaClass !in excludedTypes && disjunction.matches(node, graph) }
                .take(limit)
        } else {
            typedCandidates(graph, nodeClass, disjunction, tracker, excludedTypes, limit, storage)
        }
    }

    private fun requiresPolymorphicOrder(
        graph: Graph,
        nodeClass: Class<out Node>,
        disjunction: DirectStringDisjunction,
        excludedTypes: Set<Class<out Node>>
    ): Boolean {
        val annotationsRequested = nodeClass.isAssignableFrom(AnnotationNode::class.java) &&
            AnnotationNode::class.java !in excludedTypes
        val coercedAnnotationCandidates = annotationsRequested && disjunction.filters.any { it.coercesToString } &&
            graph.nodeCount(AnnotationNode::class.java) != 0L
        return (disjunction.filters.any { it.property == NODE_VALUE_PROPERTY } || coercedAnnotationCandidates) &&
            graph !is StringPropertyLookupOrder
    }

    private fun typedCandidates(
        graph: Graph,
        nodeClass: Class<out Node>,
        disjunction: DirectStringDisjunction,
        tracker: CypherWorkTracker?,
        excludedTypes: Set<Class<out Node>>,
        limit: Int,
        storage: StringStorageOptions
    ): Sequence<Node> {
        val sequences = mutableListOf<Sequence<Node>>()
        for (candidateFilter in disjunction.candidateFilters) {
            val candidateType = candidateFilter.type
            if (candidateType !in excludedTypes && nodeClass.isAssignableFrom(candidateType)) {
                sequences += typedCandidates(graph, candidateFilter, disjunction, tracker, limit, storage)
            }
        }
        return if (graph is StringPropertyLookupOrder) {
            mergeNodeSequences(sequences, graph::stringPropertyNodeOrder)
        } else {
            sequences.asSequence().flatten()
        }
    }

    private fun typedCandidates(
        graph: Graph,
        candidate: DirectStringCandidateFilters,
        disjunction: DirectStringDisjunction,
        tracker: CypherWorkTracker?,
        limit: Int,
        storage: StringStorageOptions
    ): Sequence<Node> {
        val type = candidate.type
        // Annotation attributes may be non-string values requiring toString(); storage cannot represent that conversion.
        if (type == AnnotationNode::class.java && candidate.filters.any { it.coercesToString }) {
            return if (graph.nodeCount(type) != 0L) {
                interruptible(graphAccess.trackWork(graph.nodes(type), tracker))
                    .filter { disjunction.matches(it, graph) }.take(limit)
            } else {
                emptySequence()
            }
        }
        val scanLimit = graph.nodeCount(type)?.takeIf { it < Int.MAX_VALUE }?.toInt()?.let { minOf(it, limit) } ?: limit
        val policy = storage.copy(raw = storage.raw || disjunction.filters.all { it.coercesToString })
        return graphAccess.stringPropertyDisjunctionCandidates(graph, type, candidate.predicates, scanLimit, tracker, policy)
            ?: fallbackTypedCandidates(graph, candidate, disjunction, tracker, scanLimit)
    }

    private fun fallbackTypedCandidates(
        graph: Graph,
        candidate: DirectStringCandidateFilters,
        disjunction: DirectStringDisjunction,
        tracker: CypherWorkTracker?,
        limit: Int
    ): Sequence<Node> {
        val accelerated = candidate.filters.map { graphAccess.stringPropertyCandidates(graph, candidate.type, it, limit, tracker) }
        return when {
            accelerated.any { it == null } ->
                interruptible(graphAccess.trackWork(graph.nodes(candidate.type), tracker)).filter { disjunction.matches(it, graph) }
            graph is StringPropertyLookupOrder -> mergeNodeSequences(accelerated.filterNotNull(), graph::stringPropertyNodeOrder)
            else -> filterOwnedNodes(graph, candidate.filters, accelerated.filterNotNull())
        }
    }

    private fun <T> interruptible(values: Sequence<T>): Sequence<T> = sequence {
        var inspected = 0
        for (value in values) {
            if ((inspected++ and CANCELLATION_POLL_MASK) == 0) {
                checkThreadInterrupted { CypherQueryCancelledException() }
            }
            yield(value)
        }
    }

    private fun <T : Node> filterOwnedNodes(
        graph: Graph,
        filters: List<DirectStringFilter>,
        sequences: List<Sequence<T>>
    ): Sequence<Node> = sequence {
        for ((index, nodes) in sequences.withIndex()) {
            for (node in nodes) {
                val ownedByEarlierFilter = (0 until index).any { earlierIndex ->
                    filters[earlierIndex].matches(node, graph)
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
}
