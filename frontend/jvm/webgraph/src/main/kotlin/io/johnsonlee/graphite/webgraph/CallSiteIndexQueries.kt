package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import it.unimi.dsi.fastutil.ints.IntArrayList

internal class CallSiteIndexQueries(private val context: MappedCallSiteStringIndex) {
    private val matchingNodeIds get() = context.matchingNodeIds
    private val projectedRows get() = context.projectedRows
    private val stringTable get() = context.stringTable
    private val nodeOrder get() = context.nodeOrder
    private val nodeIdCapacity get() = context.nodeIdCapacity
    private val rawStringPropertyId get() = context.rawStringPropertyId
    private val matches get() = context.matches

    fun matchingNodeIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?,
        limit: Int = Int.MAX_VALUE
    ): Sequence<Int> {
        if (limit <= 0) return emptySequence()
        val cacheKey = if (limit <= MAX_CALL_SITE_NODE_MATCH_CACHE_LIMIT) {
            CallSiteNodeMatchKey(predicates.toList(), limit)
        } else {
            null
        }
        val cached = cacheKey?.let { key ->
            synchronized(context) {
                matchingNodeIds[key]?.also { hit ->
                    consumeGraphWork(workConsumer, hit.nodeIds.size.coerceAtLeast(1).toLong())
                }?.nodeIds
            }
        }
        return cached?.asSequence() ?: uncachedNodeIds(predicates, workConsumer, limit, cacheKey)
    }

    private fun uncachedNodeIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?,
        limit: Int,
        cacheKey: CallSiteNodeMatchKey?
    ): Sequence<Int> {
        val ranges = matches.matchingRanges(predicates, workConsumer)
        return if (ranges.isEmpty()) {
            val empty = IntArray(0)
            cacheKey?.let { context.cacheMatchingNodeIds(it, empty) }
            empty.asSequence()
        } else {
            val source = ranges.mergedNodeIds(nodeOrder, workConsumer, limit)
            if (cacheKey == null) source else cacheMatchingNodeIdsOnComplete(cacheKey, source)
        }
    }

    fun cacheMatchingNodeIdsOnComplete(
        key: CallSiteNodeMatchKey,
        source: Sequence<Int>
    ): Sequence<Int> = sequence {
        val consumed = IntArrayList()
        for (nodeId in source) {
            consumed.add(nodeId)
            yield(nodeId)
        }
        context.cacheMatchingNodeIds(key, consumed.toIntArray())
    }

    fun projectRows(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyProjectionRow> {
        if (limit <= 0) return emptyList()
        val key = CallSiteProjectionKey(predicates.toList(), projectedProperties.toList(), limit)
        val cached = synchronized(context) {
            projectedRows[key]?.also { hit ->
                consumeGraphWork(workConsumer, hit.rows.size.coerceAtLeast(1).toLong())
            }?.rows
        }
        return cached ?: uncachedRows(key, predicates, projectedProperties, limit, workConsumer)
    }

    private fun uncachedRows(
        key: CallSiteProjectionKey,
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyProjectionRow> {
        val propertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex).toIntArray()
        require(propertyIndexes.all { it >= 0 })
        val rows = matchingNodeIds(predicates, workConsumer, limit).map { nodeId ->
            StringPropertyProjectionRow(propertyIndexes.map { propertyIndex ->
                stringTable.get(rawStringPropertyId(nodeId, propertyIndex))
            })
        }.toList()
        return context.cacheProjectedRows(key, rows)
    }

    fun aggregate(
        predicates: List<StringPropertyPredicate>,
        distinctProperty: String?,
        workConsumer: GraphWorkConsumer?
    ): StringPropertyDisjunctionAggregate {
        val ranges = matches.matchingRanges(predicates, workConsumer)
        if (ranges.isEmpty()) {
            return StringPropertyDisjunctionAggregate(0L, distinctProperty?.let { emptySet() })
        }
        val distinctPropertyIndex = distinctProperty?.let(::callSiteStringPropertyIndex)
        require(distinctPropertyIndex == null || distinctPropertyIndex >= 0)
        return ranges.aggregate(
            nodeIdCapacity,
            distinctPropertyIndex,
            rawStringPropertyId,
            stringTable,
            workConsumer
        )
    }
}
