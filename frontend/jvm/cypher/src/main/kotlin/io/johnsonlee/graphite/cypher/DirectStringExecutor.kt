package io.johnsonlee.graphite.cypher

/** Executes direct string plans with bounded graph scheduling and complete selected-value provenance. */
internal class DirectStringExecutor(
    private val sources: List<CypherGraph>,
    private val qualified: Boolean,
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations,
    private val rowProjection: CypherProjection,
    private val stringScanner: DirectStringScanner,
    private val stringScanPolicy: StringScanPolicy,
    private val indexedStringProjection: IndexedStringProjection
) {
    fun executeDirectStringFilter(
        spec: StringProjectionSpec,
        limit: Int,
        candidateSources: List<CypherGraph>,
        preferPersistedStorage: Boolean
    ): CypherResult {
        val filter = spec.filter.filters.single()
        return if (stringScanPolicy.hasTypedDirectStringCandidate(spec.nodeClass, filter)) {
            executeDirectStringDisjunctionRows(
                spec, limit, candidateSources = candidateSources, preferPersistedStorage = preferPersistedStorage)
        } else {
            executeUntypedFilter(spec, filter, limit, candidateSources)
        }
    }

    private fun executeUntypedFilter(
        spec: StringProjectionSpec,
        filter: DirectStringFilter,
        limit: Int,
        candidateSources: List<CypherGraph>
    ): CypherResult {
        val rows = mutableListOf<Map<String, Any?>>()
        for (source in candidateSources) {
            val candidates =
                graphAccess.stringPropertyCandidates(source.graph, spec.nodeClass, filter, limit - rows.size)
                    ?: graphAccess.trackWork(source.graph.nodes(spec.nodeClass)).filter { filter.matches(it, source.graph) }
            for (node in candidates) {
                rows.add(projectNode(spec, source, node))
                if (rows.size >= limit) return CypherResult(spec.columns, rows)
            }
        }
        return CypherResult(spec.columns, rows)
    }

    fun executeDirectStringDisjunction(
        spec: StringProjectionSpec,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory? = null,
        candidateSources: List<CypherGraph> = sources,
        preferMappedView: Boolean = false
    ): CypherResult {
        val indexed =
            if (nodePredicateFactory == null) {
                indexedStringProjection.executeIndexedDistinctStringProjection(spec, limit, candidateSources, preferMappedView)
            } else null
        return indexed
            ?: if (stringScanPolicy.canExecuteDirectStringDisjunctionInParallel(
                spec.nodeClass, spec.variable, spec.filter, spec.items, candidateSources)) {
                executeDirectStringDisjunctionInParallel(spec, limit, nodePredicateFactory, candidateSources, preferMappedView)
            } else {
                executeDirectStringDisjunctionSerial(spec, limit, nodePredicateFactory, candidateSources, preferMappedView)
            }
    }

    fun executeDirectStringDisjunctionRows(
        spec: StringProjectionSpec,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory? = null,
        candidateSources: List<CypherGraph> = sources,
        preferPersistedStorage: Boolean = false
    ): CypherResult {
        if (candidateSources.isEmpty()) return CypherResult(spec.columns, emptyList())
        return indexedStringProjection.executeIndexedStringProjectionRows(
            spec, limit, nodePredicateFactory, candidateSources, preferPersistedStorage)
            ?: RowScan(spec, limit, nodePredicateFactory, candidateSources, preferPersistedStorage).execute()
    }

    /** Keeps leading-probe work separate from the remaining source-ordered scan. */
    private inner class RowScan(
        private val spec: StringProjectionSpec,
        private val limit: Int,
        private val predicateFactory: DirectNodePredicateFactory?,
        private val candidates: List<CypherGraph>,
        private val preferPersisted: Boolean
    ) {
        private val balanced = usesBalancedStringSplit(candidates.size)
        private val rawLeading =
            when {
                !balanced || preferPersisted -> false
                predicateFactory != null -> false
                else -> stringScanPolicy.prefersBoundedRawLeadingProbe(spec.filter, limit)
            }
        private val tracker = if (context.workTrackingEnabled) context.workTracker else null

        fun execute(): CypherResult {
            val leading = leadingProjection()
            if (leading != null && leading.size >= limit) return CypherResult(spec.columns, leading.take(limit))
            val policy = batchingPolicy()
            return if (policy.serial) executeSerial() else executeBatches(leading, policy)
        }

        private fun leadingProjection(): List<Map<String, Any?>>? =
            when {
                rawLeading -> indexedStringProjection.projectLeadingRows(spec, limit, candidates, tracker, preferRaw = true)
                balanced && predicateFactory == null ->
                    indexedStringProjection.projectLeadingRows(spec, limit, candidates, tracker, preferRaw = false)
                else -> null
            }

        private fun batchingPolicy(): BatchPolicy {
            val parallel =
                stringScanPolicy.canExecuteDirectStringDisjunctionInParallel(
                    spec.nodeClass, spec.variable, spec.filter, spec.items, candidates)
            val retainedEligible = balanced && predicateFactory == null
            val scopedRetained =
                retainedEligible &&
                    preferPersisted &&
                    stringScanPolicy.hasRetainedStringDisjunction(spec.nodeClass, spec.filter, candidates)
            val leadingRetained =
                retainedEligible && stringScanPolicy.hasRetainedStringDisjunction(spec.nodeClass, spec.filter, candidates.take(1))
            val batchPrepared = retainedEligible && (!preferPersisted || parallel)
            val serialStorage = !rawLeading && !batchPrepared && !parallel
            return BatchPolicy(scopedRetained || serialStorage || context.workTrackingEnabled && !balanced, leadingRetained, batchPrepared)
        }

        private fun executeSerial(): CypherResult {
            val rows = mutableListOf<Map<String, Any?>>()
            for (source in candidates) {
                val predicate = predicateFactory?.invoke(source)
                val nodes =
                    stringScanner
                        .directStringCandidates(
                            source.graph,
                            spec.nodeClass,
                            spec.filter,
                            limit = if (predicate == null) limit - rows.size else Int.MAX_VALUE,
                            storage = StringStorageOptions(candidates.size, preferPersisted, mappedView = balanced))
                        .let { nodes -> predicate?.let { nodes.filter(it) } ?: nodes }
                for (node in nodes) {
                    rows += projectNode(spec, source, node)
                    if (rows.size >= limit) return CypherResult(spec.columns, rows)
                }
            }
            return CypherResult(spec.columns, rows)
        }

        private fun executeBatches(leading: List<Map<String, Any?>>?, policy: BatchPolicy): CypherResult {
            val parallelism = directStringGraphParallelism(candidates.size)
            val scanners =
                candidates.mapIndexed { index, source ->
                    val serialStorage = preferPersisted && !balanced || rawLeading && balanced && index == 0
                    stringScanner.DirectStringSourceScanner(
                        source,
                        spec,
                        tracker,
                        predicateFactory,
                        StringStorageOptions(candidates.size, serialStorage, mappedView = balanced))
                }
            val rows = mutableListOf<Map<String, Any?>>()
            if (balanced) {
                if (leading == null) probeLeadingSource(rows, policy.leadingRetained) else rows += leading
                if (rows.size >= limit) return CypherResult(spec.columns, rows.take(limit))
                scanRemaining(rows, scanners.drop(1), parallelism, policy.batchPrepared)
            } else {
                scanWaves(rows, scanners, parallelism)
            }
            return CypherResult(spec.columns, rows)
        }

        private fun probeLeadingSource(rows: MutableList<Map<String, Any?>>, retained: Boolean) {
            val source = candidates.first()
            val predicate = predicateFactory?.invoke(source)
            val nodes =
                stringScanner
                    .directStringCandidates(
                        source.graph,
                        spec.nodeClass,
                        spec.filter,
                        tracker,
                        limit = if (predicate == null) limit else Int.MAX_VALUE,
                        storage = StringStorageOptions(candidates.size, rawLeading || retained, mappedView = balanced))
                    .let { nodes -> predicate?.let { nodes.filter(it) } ?: nodes }
            for (node in nodes) {
                rows += projectNode(spec, source, node)
                if (rows.size >= limit) break
            }
        }

        private fun scanRemaining(
            rows: MutableList<Map<String, Any?>>,
            scanners: List<DirectStringScanner.DirectStringSourceScanner>,
            parallelism: Int,
            mayBatchPrepared: Boolean
        ) {
            val batchPrepared =
                mayBatchPrepared && stringScanPolicy.hasPreparedWideStringDisjunction(spec.nodeClass, spec.filter, candidates)
            val tasks = scanners.map { scanner -> { scanner.nextRows(limit) } }
            val merge: (List<Map<String, Any?>>) -> Boolean = { batch ->
                mergeRows(rows, batch)
                rows.size >= limit
            }
            if (batchPrepared) runDirectStringTasksWithFixedWorkersInOrderUntil(tasks, parallelism, merge)
            else runDirectStringTasksInOrderUntil(tasks, parallelism, merge)
        }

        private fun scanWaves(
            rows: MutableList<Map<String, Any?>>,
            scanners: List<DirectStringScanner.DirectStringSourceScanner>,
            parallelism: Int
        ) {
            var start = 0
            while (start < scanners.size && rows.size < limit) {
                val wave = scanners.subList(start, minOf(scanners.size, start + parallelism))
                runDirectStringTasks(wave.map { scanner -> { scanner.nextRows(limit) } }, parallelism).forEach { mergeRows(rows, it) }
                start += wave.size
            }
        }

        private fun mergeRows(rows: MutableList<Map<String, Any?>>, batch: List<Map<String, Any?>>) {
            val remaining = limit - rows.size
            if (remaining > 0) rows += batch.take(remaining)
        }
    }

    private data class BatchPolicy(val serial: Boolean, val leadingRetained: Boolean, val batchPrepared: Boolean)

    private fun projectNode(spec: StringProjectionSpec, source: CypherGraph, node: io.johnsonlee.graphite.core.Node): Map<String, Any?> {
        val candidate = graphAccess.nodeValue(source, node)
        val bindings = mutableMapOf<String, Any?>(spec.variable to candidate)
        rowOperations.addProvenance(bindings, candidate)
        return rowProjection.projectRow(spec.items, spec.columns, bindings)
    }

    private fun executeDirectStringDisjunctionSerial(
        spec: StringProjectionSpec,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory?,
        candidateSources: List<CypherGraph>,
        preferMappedView: Boolean
    ): CypherResult {
        val nodeClass = spec.nodeClass
        val variable = spec.variable
        val filter = spec.filter
        val items = spec.items
        val columns = spec.columns
        val rows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        for (source in candidateSources) {
            val nodePredicate = nodePredicateFactory?.invoke(source)
            val candidates =
                stringScanner
                    .directStringCandidates(
                        graph = source.graph,
                        nodeClass = nodeClass,
                        disjunction = filter,
                        storage = StringStorageOptions(candidateSources.size, false, false, preferMappedView))
                    .let { nodes -> nodePredicate?.let { predicate -> nodes.filter(predicate) } ?: nodes }
            for (node in candidates) {
                val candidate = graphAccess.nodeValue(source, node)
                val bindings = mutableMapOf<String, Any?>(variable to candidate)
                rowOperations.addProvenance(bindings, candidate)
                addDistinctRow(rows, rowProjection.projectRow(items, columns, bindings), limit)
                if (!qualified && rows.size >= limit) return CypherResult(columns, rows.values.toList())
            }
        }
        return CypherResult(columns, rows.values.toList())
    }

    /**
     * Scans independent graphs concurrently but merges rows in source order. Each scanner pauses after a bounded batch of local distinct
     * rows. Once the global LIMIT is known, workers drain only for those selected values so later occurrences can add complete provenance
     * without retaining an unbounded per-graph result set.
     */
    private fun executeDirectStringDisjunctionInParallel(
        spec: StringProjectionSpec,
        limit: Int,
        nodePredicateFactory: DirectNodePredicateFactory?,
        candidateSources: List<CypherGraph>,
        preferMappedView: Boolean
    ): CypherResult {
        val nodeClass = spec.nodeClass
        val variable = spec.variable
        val filter = spec.filter
        val items = spec.items
        val columns = spec.columns
        val tracker = if (context.workTrackingEnabled) context.workTracker else null
        val scanners =
            candidateSources.map { source ->
                stringScanner.DirectStringSourceScanner(
                    source = source,
                    tracker = tracker,
                    nodePredicateFactory = nodePredicateFactory,
                    projection = StringProjectionSpec(nodeClass, variable, filter, items, columns),
                    storage = StringStorageOptions(candidateSources.size, false, false, preferMappedView))
            }
        val rows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        var waveStart = 0
        while (waveStart < scanners.size && rows.size < limit) {
            val graphParallelism = directStringGraphParallelism(candidateSources.size)
            val wave = scanners.subList(waveStart, minOf(scanners.size, waveStart + graphParallelism))
            val initial = runDirectStringTasks(wave.map { scanner -> { scanner.nextDistinctRows(limit) } }, graphParallelism)
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
            val selectedMatcher = stringScanner.DirectStringSelectedRowMatcher(selected, items, columns)
            val hits =
                runDirectStringTasks(
                    scanners.map { scanner -> { scanner.collectRemainingSelectedRows(selectedMatcher) } },
                    directStringGraphParallelism(candidateSources.size))
            hits.forEachIndexed { index, visibleRows ->
                val graphId = scanners[index].source.id
                visibleRows.forEach { visible ->
                    val row = rows.getValue(visible)
                    @Suppress("UNCHECKED_CAST") val provenance = row[INTERNAL_PROVENANCE_KEY] as? Set<String> ?: emptySet()
                    row[INTERNAL_PROVENANCE_KEY] = provenance + graphId
                }
            }
        }
        return CypherResult(columns, rows.values.toList())
    }
}
