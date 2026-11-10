package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.PreparedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.ReleasableStringPropertyDisjunctionCache
import io.johnsonlee.graphite.graph.RetainedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionDistinctProjection
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionProjection
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate

/** Direct stored-string row projections, including source-complete DISTINCT provenance. */
internal class IndexedStringProjection(
    private val sources: List<CypherGraph>,
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations,
    private val stringScanner: DirectStringScanner
) {
    fun projectLeadingRows(
        spec: StringProjectionSpec,
        limit: Int,
        candidateSources: List<CypherGraph>,
        tracker: CypherWorkTracker?,
        preferRaw: Boolean
    ): List<Map<String, Any?>>? =
        when (spec.nodeClass) {
            CallSiteNode::class.java,
            Node::class.java -> projectLeadingSourceRows(spec, limit, candidateSources, tracker, preferRaw)
            else -> null
        }

    private fun projectLeadingSourceRows(
        spec: StringProjectionSpec,
        limit: Int,
        candidateSources: List<CypherGraph>,
        tracker: CypherWorkTracker?,
        preferRaw: Boolean
    ): List<Map<String, Any?>>? {
        val source = candidateSources.first()
        return compileRegularRequest(spec, listOf(source), allowGraphId = true)?.let { plan ->
            val storageProperties = plan.properties.filter { it != GRAPH_ID_PROPERTY }
            (source.graph as? StringPropertyDisjunctionProjection)
                ?.projectStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    plan.predicates,
                    storageProperties,
                    limit,
                    graphAccess.stringStorageWorkConsumer(
                        candidateSources.size, tracker, preferRaw = preferRaw, preferMappedView = !preferRaw))
                ?.let { projectedRows ->
                    val provenance = setOf(source.id)
                    projectedRows.map { raw ->
                        LinkedHashMap<String, Any?>(spec.columns.size + 1).apply {
                            var storageIndex = 0
                            spec.columns.indices.forEach { index ->
                                this[spec.columns[index]] =
                                    if (plan.properties[index] == GRAPH_ID_PROPERTY) source.id else raw.values[storageIndex++]
                            }
                            put(INTERNAL_PROVENANCE_KEY, provenance)
                        }
                    }
                }
        }
    }

    fun executeIndexedStringProjectionRows(
        spec: StringProjectionSpec,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory?,
        candidateSources: List<CypherGraph>,
        preferPersistedStorage: Boolean
    ): CypherResult? =
        if (nodePredicateFactory != null || candidateSources.size > 1 && !preferPersistedStorage) {
            null
        } else {
            compileRegularRequest(spec, candidateSources, allowGraphId = false)?.let { plan ->
                resolveRegularCapabilities(plan, candidateSources)?.let { projections ->
                    collectRegularRows(spec, plan, projections, limit, candidateSources)
                }
            }
        }

    private data class RegularRequest(val properties: List<String>, val predicates: List<StringPropertyPredicate>)

    private fun projectionProperties(spec: StringProjectionSpec): List<String>? =
        spec.items
            .mapNotNull { item ->
                (item.expression as? CypherExpr.Property)?.takeIf { it.expression == CypherExpr.Variable(spec.variable) }?.propertyName
            }
            .takeIf { it.size == spec.items.size }

    private fun supportsRegularClass(spec: StringProjectionSpec, candidateSources: List<CypherGraph>): Boolean =
        when (spec.nodeClass) {
            CallSiteNode::class.java -> true
            Node::class.java -> candidateSources.none { it.graph.nodeCount(AnnotationNode::class.java) != 0L }
            else -> false
        }

    private fun compileRegularRequest(
        spec: StringProjectionSpec,
        candidateSources: List<CypherGraph>,
        allowGraphId: Boolean
    ): RegularRequest? {
        val properties = projectionProperties(spec)
        val validProperties =
            properties?.all { it in CALL_SITE_DIRECT_STRING_PROPERTIES || allowGraphId && it == GRAPH_ID_PROPERTY } == true
        val validFilters = spec.filter.filters.all { it.property in CALL_SITE_DIRECT_STRING_PROPERTIES }
        return if (supportsRegularClass(spec, candidateSources) && validProperties && validFilters) {
            RegularRequest(
                checkNotNull(properties),
                spec.filter.filters.map { StringPropertyPredicate(it.property, it.transform, it.mode, it.expected) })
        } else null
    }

    private fun resolveRegularCapabilities(
        plan: RegularRequest,
        candidateSources: List<CypherGraph>
    ): List<StringPropertyDisjunctionProjection>? {
        val projections = candidateSources.mapNotNull { it.graph as? StringPropertyDisjunctionProjection }
        val completeCapabilities = projections.size == candidateSources.size
        val allRetained =
            completeCapabilities &&
                (candidateSources.size <= 1 ||
                    candidateSources.all {
                        (it.graph as? RetainedStringPropertyDisjunctionLookup)?.hasRetainedStringPropertyDisjunction(
                            CallSiteNode::class.java, plan.predicates) == true
                    })
        return projections.takeIf { it.size == candidateSources.size && allRetained }
    }

    private fun collectRegularRows(
        spec: StringProjectionSpec,
        plan: RegularRequest,
        projections: List<StringPropertyDisjunctionProjection>,
        limit: Int,
        candidateSources: List<CypherGraph>
    ): CypherResult? {
        val tracker = if (context.workTrackingEnabled) context.workTracker else null
        val rows = mutableListOf<Map<String, Any?>>()
        var sourceIndex = 0
        var done = false
        var failed = false
        var singleResult: CypherResult? = null
        while (sourceIndex < candidateSources.size && !done) {
            val source = candidateSources[sourceIndex]
            val projectedRows =
                projections[sourceIndex].projectStringPropertyDisjunction(
                    CallSiteNode::class.java, plan.predicates, plan.properties, limit - rows.size, tracker)
            if (projectedRows == null) {
                failed = true
                done = true
            } else {
                val projected = DirectProjectionResultCache.getOrCreate(projectedRows, spec.columns, source.id)
                if (candidateSources.size == 1) {
                    singleResult = projected
                    done = true
                } else {
                    rows.addAll(projected.rows.take(limit - rows.size))
                    done = rows.size >= limit
                }
            }
            sourceIndex++
        }
        return if (failed) null else singleResult ?: CypherResult(spec.columns, rows)
    }

    fun executeIndexedDistinctStringProjection(
        spec: StringProjectionSpec,
        limit: Int,
        candidateSources: List<CypherGraph> = sources,
        preferMappedView: Boolean = false
    ): CypherResult? {
        val plan = compileDistinctRequest(spec, candidateSources) ?: return null
        return DistinctExecution(spec, limit, candidateSources, preferMappedView, plan).execute()
    }

    private fun compileDistinctRequest(spec: StringProjectionSpec, candidateSources: List<CypherGraph>): DistinctRequest? {
        val properties = projectionProperties(spec)
        val validProperties =
            properties?.all {
                it == GRAPH_ID_PROPERTY || it in CALL_SITE_DIRECT_STRING_PROPERTIES || it in CALL_SITE_NULL_STRING_PROPERTIES
            } == true
        val callSiteFilters = spec.filter.filters.filter { it.property in CALL_SITE_DIRECT_STRING_PROPERTIES }
        val projections = candidateSources.mapNotNull { it.graph as? StringPropertyDisjunctionDistinctProjection }
        val orders = candidateSources.mapNotNull { it.graph as? StringPropertyLookupOrder }
        val supportsCallSites = spec.nodeClass.isAssignableFrom(CallSiteNode::class.java)
        val annotationsUnsupported =
            supportsCallSites &&
                spec.nodeClass.isAssignableFrom(AnnotationNode::class.java) &&
                candidateSources.any { it.graph.nodeCount(AnnotationNode::class.java) != 0L }
        val supportedClass = supportsCallSites && !annotationsUnsupported
        val completeCapabilities = projections.size == candidateSources.size && orders.size == candidateSources.size
        return when {
            !supportedClass || !validProperties -> null
            callSiteFilters.isEmpty() || !completeCapabilities -> null
            else ->
                DistinctRequest(
                    checkNotNull(properties),
                    callSiteFilters.map { StringPropertyPredicate(it.property, it.transform, it.mode, it.expected) },
                    projections,
                    orders)
        }
    }

    private data class DistinctRequest(
        val properties: List<String>,
        val predicates: List<StringPropertyPredicate>,
        val projections: List<StringPropertyDisjunctionDistinctProjection>,
        val orders: List<StringPropertyLookupOrder>
    )

    /** One execution owns its rows, source completion and provenance observations. */
    private inner class DistinctExecution(
        private val spec: StringProjectionSpec,
        private val limit: Int,
        private val candidateSources: List<CypherGraph>,
        private val preferMappedView: Boolean,
        private val plan: DistinctRequest
    ) {
        private val nodeClass
            get() = spec.nodeClass

        private val filter
            get() = spec.filter

        private val items
            get() = spec.items

        private val columns
            get() = spec.columns

        private val projectedProperties
            get() = plan.properties

        private val predicates
            get() = plan.predicates

        private val projections
            get() = plan.projections

        private val orders
            get() = plan.orders

        private val tracker = if (context.workTrackingEnabled) context.workTracker else null
        private val rows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        private val zeroHitSources = BooleanArray(candidateSources.size)
        private val projectedRowsBySource = arrayOfNulls<List<OrderedProjectedRow>>(candidateSources.size)

        fun execute(): CypherResult {
            collectLeadingRows()
            if (rows.size >= limit) completeProvenance()
            return CypherResult(columns, rows.values.toList())
        }

        private fun collectLeadingRows() {
            val balanced = usesBalancedStringSplit(candidateSources.size)
            var waveStart = 0
            if (balanced) {
                // Probe the leading graph with the storage half of the NCPU budget. If it fills LIMIT,
                // avoid initializing an entire speculative graph wave; all graphs are still checked
                // below for complete DISTINCT provenance of the selected tuples.
                mergeSource(projectSource(0))
                waveStart = 1
                if (rows.size < limit) {
                    runDirectStringTasksInOrderUntil(
                        candidateSources.indices.drop(waveStart).map { sourceIndex -> { projectSource(sourceIndex) } },
                        directStringGraphParallelism(candidateSources.size),
                        ::mergeSource)
                }
                waveStart = candidateSources.size
            }
            while (waveStart < candidateSources.size && rows.size < limit) {
                val graphParallelism = directStringGraphParallelism(candidateSources.size)
                val waveEnd = minOf(candidateSources.size, waveStart + graphParallelism)
                val localRows =
                    runDirectStringTasks((waveStart until waveEnd).map { sourceIndex -> { projectSource(sourceIndex) } }, graphParallelism)
                localRows.forEach(::mergeSource)
                waveStart = waveEnd
            }
        }

        private fun completeProvenance() {
            val selected = rows.keys.toSet()
            // Admission excludes dynamic annotation properties; every selected column is String or null.
            val selectedValues = selected.mapTo(linkedSetOf()) { row -> columns.map { row[it] as String? } }
            val selectedMatcher = stringScanner.DirectStringSelectedRowMatcher(selected, items, columns)
            val knownRowsBySource =
                projectedRowsBySource.map { projectedRows ->
                    projectedRows?.mapTo(hashSetOf()) { ordered -> rowOperations.visibleRow(ordered.row) }
                }
            val provenanceSourceIndexes =
                candidateSources.indices.filter { sourceIndex ->
                    !zeroHitSources[sourceIndex] && knownRowsBySource[sourceIndex]?.containsAll(selected) != true
                }
            val hits =
                runDirectStringTasks(
                    provenanceSourceIndexes.map { sourceIndex ->
                        {
                            val source = candidateSources[sourceIndex]
                            // A single source already supplied every selected row and is filtered out above.
                            val storageWorkConsumer = graphAccess.stringStorageWorkConsumer(
                                candidateSources.size, tracker, preferMappedView = preferMappedView)
                            val sourceSelectedValues = storageSelectedValues(selectedValues, projectedProperties, source.id)
                            val rawHits = mutableSetOf<Map<String, Any?>>()
                            if (sourceSelectedValues.isNotEmpty()) {
                                projections[sourceIndex]
                                    .distinctStringPropertyDisjunction(
                                        CallSiteNode::class.java,
                                        predicates,
                                        projectedProperties,
                                        sourceSelectedValues.size,
                                        sourceSelectedValues,
                                        storageWorkConsumer)
                                    .orEmpty()
                                    .mapTo(rawHits) { hit ->
                                        rowOperations.visibleRow(rawProjectionRow(hit.values, projectedProperties, columns, source.id))
                                    }
                            }
                            val matcher = selectedMatcher.cursor(source)
                            for (node in
                                stringScanner.directStringCandidates(
                                    graph = source.graph,
                                    nodeClass = nodeClass,
                                    disjunction = filter,
                                    tracker = tracker,
                                    excludedTypes = setOf(CallSiteNode::class.java))) {
                                matcher.match(node)?.let(rawHits::add)
                                if (rawHits.size >= selected.size) break
                            }
                            rawHits
                        }
                    },
                    directStringGraphParallelism(candidateSources.size))
            hits.forEachIndexed { hitIndex, visibleRows ->
                val sourceIndex = provenanceSourceIndexes[hitIndex]
                val graphId = candidateSources[sourceIndex].id
                visibleRows.forEach { visible ->
                    val row = rows.getValue(visible)
                    @Suppress("UNCHECKED_CAST") val provenance = row[INTERNAL_PROVENANCE_KEY] as? Set<String> ?: emptySet()
                    row[INTERNAL_PROVENANCE_KEY] = provenance + graphId
                }
            }
        }

        private fun projectSource(sourceIndex: Int): IndexedProjectedRows {
            val source = candidateSources[sourceIndex]
            val forceSerialStorage =
                candidateSources.size == 1 &&
                    (source.graph as? PreparedStringPropertyDisjunctionLookup)?.hasPreparedStringPropertyDisjunction(
                        CallSiteNode::class.java, predicates) == true
            val storageWorkConsumer =
                graphAccess.stringStorageWorkConsumer(
                    candidateSources.size, tracker, forceSerial = forceSerialStorage, preferMappedView = preferMappedView)
            val projected =
                projections[sourceIndex].distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    projectedProperties,
                    limit,
                    selectedValues = null,
                    workConsumer = storageWorkConsumer) ?: error("Distinct projection capability became unavailable")
            val sourceRows =
                projected
                    .map { raw ->
                        OrderedProjectedRow(raw.encounterOrder, rawProjectionRow(raw.values, projectedProperties, columns, source.id))
                    }
                    .toMutableList()

            val seenGeneric = HashSet<Map<String, Any?>>()
            for (node in
                stringScanner.directStringCandidates(
                    graph = source.graph,
                    nodeClass = nodeClass,
                    disjunction = filter,
                    tracker = tracker,
                    excludedTypes = setOf(CallSiteNode::class.java))) {
                val row = projectedNodeRow(source, node, projectedProperties, columns)
                val visible = rowOperations.visibleRow(row)
                if (seenGeneric.add(visible)) {
                    sourceRows += OrderedProjectedRow(orders[sourceIndex].stringPropertyNodeOrder(node), row)
                    if (seenGeneric.size >= limit) break
                }
            }
            val distinct = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
            sourceRows.sortedBy(OrderedProjectedRow::encounterOrder).forEach { ordered -> addDistinctRow(distinct, ordered.row, limit) }
            return IndexedProjectedRows(sourceIndex, distinct.values.mapIndexed { index, row -> OrderedProjectedRow(index.toLong(), row) })
        }

        private fun mergeSource(projected: IndexedProjectedRows): Boolean {
            projectedRowsBySource[projected.sourceIndex] = projected.rows
            if (projected.rows.isEmpty()) {
                zeroHitSources[projected.sourceIndex] = true
                if (candidateSources.size > 1) {
                    (candidateSources[projected.sourceIndex].graph as? ReleasableStringPropertyDisjunctionCache)
                        ?.releaseStringPropertyDisjunctionCache()
                }
            }
            projected.rows.forEach { ordered -> addDistinctRow(rows, ordered.row, limit) }
            return rows.size >= limit
        }
    }

    private data class OrderedProjectedRow(val encounterOrder: Long, val row: MutableMap<String, Any?>)

    private data class IndexedProjectedRows(val sourceIndex: Int, val rows: List<OrderedProjectedRow>)
}
