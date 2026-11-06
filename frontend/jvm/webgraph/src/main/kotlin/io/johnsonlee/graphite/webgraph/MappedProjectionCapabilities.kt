package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionDistinctProjection
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionProjection
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import io.johnsonlee.graphite.graph.WorkAwareStreamingStringPropertyProjection
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionAggregation

internal class MappedProjectionCapabilities(private val context: MappedGraphContext) :
    WorkAwareStringPropertyDisjunctionAggregation,
    StringPropertyDisjunctionProjection,
    WorkAwareStreamingStringPropertyProjection,
    StringPropertyDisjunctionDistinctProjection {
    override fun aggregateStringPropertyDisjunction(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>,
        distinctProperty: String?
    ): StringPropertyDisjunctionAggregate? = context.aggregateStringPropertyDisjunction(type, predicates, distinctProperty)

    override fun aggregateStringPropertyDisjunction(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>,
        distinctProperty: String?,
        workConsumer: GraphWorkConsumer
    ): StringPropertyDisjunctionAggregate? = context.aggregateStringPropertyDisjunction(type, predicates, distinctProperty, workConsumer)

    override fun distinctStringPropertyDisjunction(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        selectedValues: Set<List<String?>>?,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow>? =
        context.distinctStringPropertyDisjunction(type, predicates, projectedProperties, limit, selectedValues, workConsumer)

    override fun forEachStringPropertyProjection(
        type: Class<out Node>,
        projectedProperties: List<String>,
        checkCancelled: () -> Unit,
        consumer: (StringPropertyProjectionRow) -> Unit
    ): Boolean = context.forEachStringPropertyProjection(type, projectedProperties, checkCancelled, consumer)

    override fun forEachStringPropertyProjection(
        type: Class<out Node>,
        projectedProperties: List<String>,
        preflightWorkConsumer: GraphWorkConsumer?,
        checkCancelled: () -> Unit,
        consumer: (StringPropertyProjectionRow) -> Unit
    ): Boolean = context.forEachStringPropertyProjection(type, projectedProperties, preflightWorkConsumer, checkCancelled, consumer)

    override fun projectStringPropertyDisjunction(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyProjectionRow>? =
        context.projectStringPropertyDisjunction(type, predicates, projectedProperties, limit, workConsumer)
}
