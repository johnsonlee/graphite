package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.PreparedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.RetainedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookupStrategy
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyLookup
import io.johnsonlee.graphite.graph.WorkAwareTransformedStringPropertyLookup

internal class MappedStringLookupCapabilities(private val context: MappedGraphContext) :
    WorkAwareStringPropertyLookup,
    WorkAwareTransformedStringPropertyLookup,
    WorkAwareStringPropertyDisjunctionLookup,
    PreparedStringPropertyDisjunctionLookup,
    RetainedStringPropertyDisjunctionLookup,
    StringPropertyDisjunctionLookupStrategy {
    override fun <T : Node> nodesByStringProperty(
        type: Class<T>,
        property: String,
        mode: StringMatchMode,
        expected: String,
        limit: Int
    ): Sequence<T>? = context.nodesByStringProperty(type, property, mode, expected, limit)

    override fun <T : Node> nodesByStringProperty(
        type: Class<T>,
        property: String,
        mode: StringMatchMode,
        expected: String,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): Sequence<T>? = context.nodesByStringProperty(type, property, mode, expected, limit, workConsumer)

    override fun <T : Node> nodesByTransformedStringProperty(
        type: Class<T>,
        property: String,
        transform: StringValueTransform,
        mode: StringMatchMode,
        expected: String,
        limit: Int
    ): Sequence<T>? = context.nodesByTransformedStringProperty(type, property, transform, mode, expected, limit)

    override fun <T : Node> nodesByTransformedStringProperty(
        type: Class<T>,
        property: String,
        transform: StringValueTransform,
        mode: StringMatchMode,
        expected: String,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): Sequence<T>? = context.nodesByTransformedStringProperty(type, property, transform, mode, expected, limit, workConsumer)

    override fun <T : Node> nodesByStringPropertyDisjunction(
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int
    ): Sequence<T>? = context.nodesByStringPropertyDisjunction(type, predicates, limit)

    override fun <T : Node> nodesByStringPropertyDisjunction(
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): Sequence<T>? = context.nodesByStringPropertyDisjunction(type, predicates, limit, workConsumer)

    override fun prefersSerialStringPropertyDisjunction(type: Class<out Node>, predicates: List<StringPropertyPredicate>): Boolean =
        context.prefersSerialStringPropertyDisjunction(type, predicates)

    override fun hasPreparedStringPropertyDisjunction(type: Class<out Node>, predicates: List<StringPropertyPredicate>): Boolean =
        context.hasPreparedStringPropertyDisjunction(type, predicates)

    override fun hasRetainedStringPropertyDisjunction(type: Class<out Node>, predicates: List<StringPropertyPredicate>): Boolean =
        context.hasRetainedStringPropertyDisjunction(type, predicates)
}
