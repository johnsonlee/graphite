package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.NodeIdCandidateLookup
import java.util.function.IntPredicate
import io.johnsonlee.graphite.graph.NodePropertyTextCandidates
import io.johnsonlee.graphite.graph.propertyTextFragments
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.nodesByStringProperty
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import io.johnsonlee.graphite.graph.nodesByTransformedStringProperty
import io.johnsonlee.graphite.graph.methods

internal class WorkTrackingSequence<T>(
    private val source: Sequence<T>,
    private val workTracker: CypherWorkTracker
) : Sequence<T> {
    override fun iterator(): Iterator<T> {
        val sourceIterator = source.iterator()
        return object : Iterator<T> {
            override fun hasNext(): Boolean = sourceIterator.hasNext()

            override fun next(): T {
                if (!sourceIterator.hasNext()) throw NoSuchElementException()
                workTracker.consume()
                return sourceIterator.next()
            }
        }
    }
}

internal data class NodeCursor(
    val source: CypherGraph,
    val node: Node,
    val value: Any
)

internal data class EdgeCursor(
    val source: CypherGraph,
    val edge: Edge,
    val value: Any
)

/** Namespace-aware graph access and work accounting used by planners and matchers. */
internal class PipelineGraphAccess(
    val sources: List<CypherGraph>,
    val qualified: Boolean,
    private val context: PipelineExecutionContext
) {
    private val graph: Graph get() = sources.single().graph
    private val defaultGraph: Graph? get() = sources.singleOrNull()?.graph

    fun edgesForDirection(
        node: NodeCursor,
        direction: Direction,
        edgeClass: Class<out Edge>?
    ): Sequence<EdgeCursor> {
        val graph = node.source.graph
        val nodeId = node.node.id
        val tracked = context.workTrackingEnabled && context.workTracker != null
        val edges = when (direction) {
            Direction.OUTGOING ->
                if (!tracked && edgeClass != null) graph.outgoing(nodeId, edgeClass) else graph.outgoing(nodeId)
            Direction.INCOMING ->
                if (!tracked && edgeClass != null) graph.incoming(nodeId, edgeClass) else graph.incoming(nodeId)
            Direction.BOTH -> {
                val outgoing =
                    if (!tracked && edgeClass != null) graph.outgoing(nodeId, edgeClass) else graph.outgoing(nodeId)
                val incoming =
                    if (!tracked && edgeClass != null) graph.incoming(nodeId, edgeClass) else graph.incoming(nodeId)
                outgoing + incoming
            }
        }
        val candidates = trackWork(edges)
        val filtered = if (tracked && edgeClass != null) {
            candidates.filter(edgeClass::isInstance)
        } else {
            candidates
        }
        return filtered.map { edge -> EdgeCursor(node.source, edge, edgeValue(node.source, edge)) }
    }

    fun resolveTargetId(edge: Edge, sourceId: NodeId, direction: Direction): NodeId =
        when (direction) {
            Direction.OUTGOING -> edge.to
            Direction.INCOMING -> edge.from
            Direction.BOTH -> if (edge.from == sourceId) edge.to else edge.from
        }

    fun findLastBoundNode(bindings: Map<String, Any?>): NodeCursor? =
        nodeCursor(bindings[INTERNAL_CURRENT_NODE_KEY])

    

    /** Generated qualified identities can be checked using only namespace and graph-local ID. */
    fun qualifiedIdCandidates(
        nodeClass: Class<out Node>,
        condition: CypherExpr,
        variable: String,
        candidateSources: List<CypherGraph>
    ): Sequence<Any>? {
        if (!qualified) return null
        return qualifiedIdNeedle(condition, variable)?.let { needle ->
        val tracker = if (context.workTrackingEnabled) context.workTracker else null
        candidateSources.asSequence().flatMap { source ->
            val prefix = source.id + ":"
            val lookup = (source.graph as? NodeIdCandidateLookup)?.takeUnless { prefix.contains(needle) }
            val candidates = if (lookup == null) null else {
                val text = StringBuilder(prefix)
                lookup.nodesMatchingId(nodeClass, IntPredicate { id ->
                    text.setLength(prefix.length)
                    text.append(id)
                    text.indexOf(needle) >= 0
                }, tracker)
            }
            (candidates ?: trackWork(source.graph.nodes(nodeClass), tracker)).map { node -> nodeValue(source, node) }
        }
        }
    }

    private fun qualifiedIdNeedle(condition: CypherExpr, variable: String): String? {
        val predicate = condition as? CypherExpr.StringOp ?: return null
        return (predicate.left as? CypherExpr.Property)?.takeIf { property ->
            predicate.op == "CONTAINS" && property.expression == CypherExpr.Variable(variable) &&
                property.propertyName in setOf(QUALIFIED_ID_PROPERTY, ELEMENT_ID_PROPERTY)
        }?.let { context.stringValue(predicate.right) }
    }

    private fun hasDeclaredNodeProperties(graph: Graph, nodeClass: Class<out Node>): Boolean {
        val table = graph.declaredTypes()
        return (nodeClass.isAssignableFrom(FieldNode::class.java) && table.fields.isNotEmpty()) ||
            ((nodeClass.isAssignableFrom(ParameterNode::class.java) || nodeClass.isAssignableFrom(ReturnNode::class.java)) &&
                table.methods.isNotEmpty())
    }

    /** A storage candidate is never an answer: the complete ANY predicate still runs on every survivor. */
    fun dynamicPropertyCandidates(
        nodeClass: Class<out Node>,
        condition: CypherExpr,
        variable: String,
        candidateSources: List<CypherGraph>
    ): Sequence<Any>? {
        val plan = (condition as? CypherExpr.PredicateFunction)?.let(DynamicPropertyContainsPlan::compile)
            ?.takeIf { it.nodeVariable == variable } ?: return null
        return context.stringValue(plan.term)?.let(::propertyTextFragments)?.takeIf { it.isNotEmpty() }?.let { fragments ->
            dynamicPropertyCandidates(nodeClass, fragments, candidateSources)
        }
    }

    private fun dynamicPropertyCandidates(
        nodeClass: Class<out Node>,
        fragments: List<String>,
        candidateSources: List<CypherGraph>
    ): Sequence<Any> {
        val fragment = fragments.first()
        val tracker = if (context.workTrackingEnabled) context.workTracker else null
        return candidateSources.asSequence().flatMap { source ->
            // Metadata is not stored in the node record. A graph ID hit (including
            // the prefix of an element ID) must retain every node in that graph.
            val lookup = (source.graph as? NodePropertyTextCandidates)
                ?.takeUnless {
                    qualified && source.id.contains(fragment) ||
                        !it.includesDeclaredTypeProperties && hasDeclaredNodeProperties(source.graph, nodeClass)
                }
            val candidates = lookup?.propertyTextCandidates(nodeClass, fragments, tracker)
                ?: trackWork(source.graph.nodes(nodeClass), tracker)
            candidates.map { node -> nodeValue(source, node) }
        }
    }

    fun <T : Node> nodeCandidates(
        type: Class<T>,
        candidateSources: List<CypherGraph> = sources
    ): Sequence<Any> =
        if (qualified) {
            candidateSources.asSequence().flatMap { source ->
                trackWork(source.graph.nodes(type)).map { node -> QualifiedNode(source.id, source.graph, node) }
            }
        } else {
            trackWork(graph.nodes(type)).map { it as Any }
        }

    fun methodCandidates(): Sequence<Any> {
        val tracker = if (context.workTrackingEnabled) context.workTracker else null
        val cancellationConsumer = tracker?.let { activeTracker ->
            MethodMetadataScanConsumer { activeTracker.checkCancelled() }
        }
        return sources.asSequence().flatMap { source ->
            val methods = cancellationConsumer?.let { source.graph.methods(MethodPattern(), it) }
                ?: source.graph.methods(MethodPattern())
            methods.map { method -> MethodValue(source.id.takeIf { qualified }, method, source.graph) }
        }
    }

    fun <T> trackWork(
        values: Sequence<T>,
        tracker: CypherWorkTracker? = if (context.workTrackingEnabled) context.workTracker else null
    ): Sequence<T> {
        return if (!context.workTrackingEnabled) {
            values
        } else {
            tracker?.let { WorkTrackingSequence(values, it) } ?: values
        }
    }

    fun trackedNode(graph: Graph, nodeId: NodeId): Node? {
        if (context.workTrackingEnabled) context.workTracker?.consume()
        return graph.node(nodeId)
    }

    fun <T : Node> stringPropertyCandidates(
        graph: Graph,
        type: Class<T>,
        filter: DirectStringFilter,
        limit: Int,
        tracker: CypherWorkTracker? = if (context.workTrackingEnabled) context.workTracker else null
    ): Sequence<T>? {
        return if (filter.transform == null && tracker != null) {
            graph.nodesByStringProperty(type, filter.property, filter.mode, filter.expected, limit, tracker)
        } else if (filter.transform == null) {
            graph.nodesByStringProperty(type, filter.property, filter.mode, filter.expected, limit)
        } else if (tracker != null) {
            graph.nodesByTransformedStringProperty(
                type,
                filter.property,
                filter.transform,
                filter.mode,
                filter.expected,
                limit,
                tracker
            )
        } else {
            graph.nodesByTransformedStringProperty(
                type,
                filter.property,
                filter.transform,
                filter.mode,
                filter.expected,
                limit
            )
        }
    }

    fun <T : Node> stringPropertyDisjunctionCandidates(
        graph: Graph,
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int,
        tracker: CypherWorkTracker? = if (context.workTrackingEnabled) context.workTracker else null,
        storage: StringStorageOptions = StringStorageOptions(sources.size)
    ): Sequence<T>? {
        val storageWorkConsumer = stringStorageWorkConsumer(
            storage.sourceCount,
            tracker,
            storage.serial,
            storage.raw,
            storage.mappedView
        )
        val workAware = graph.nodesByStringPropertyDisjunction(type, predicates, limit, storageWorkConsumer)
        return if (workAware != null || tracker != null) {
            workAware
        } else {
            graph.nodesByStringPropertyDisjunction(type, predicates, limit)
        }
    }

    fun stringStorageWorkConsumer(
        sourceCount: Int,
        tracker: CypherWorkTracker?,
        forceSerial: Boolean = false,
        preferRaw: Boolean = false,
        preferMappedView: Boolean = false
    ): GraphWorkConsumer = directStringStorageWorkConsumer(
        sourceCount,
        configuredGraphWorkers = configuredDirectStringParallelism,
        forceSerial = forceSerial,
        preferRaw = preferRaw,
        preferMappedView = preferMappedView,
        consumeBatch = tracker?.let { activeTracker ->
            { workUnits -> activeTracker.consume(workUnits) }
        }
    )

    fun nodeCursor(value: Any?): NodeCursor? = when (value) {
        is QualifiedNode -> NodeCursor(CypherGraph(value.graphId, value.graph), value.node, value)
        is Node -> NodeCursor(sources.single(), value, value)
        else -> null
    }

    fun nodeValue(source: CypherGraph, node: Node): Any =
        if (qualified) QualifiedNode(source.id, source.graph, node) else node

    fun edgeValue(source: CypherGraph, edge: Edge): Any =
        if (qualified) QualifiedEdge(source.id, source.graph, edge) else edge

    fun nodeProperty(value: Any, property: String): Any? = when (value) {
        is MethodValue -> value.property(property)
        is QualifiedNode -> when (property) {
            GRAPH_ID_PROPERTY -> value.graphId
            ELEMENT_ID_PROPERTY, QUALIFIED_ID_PROPERTY -> value.elementId
            else -> NodePropertyAccessor.getProperty(value.node, property, value.graph)
        }
        is Node -> NodePropertyAccessor.getProperty(value, property, defaultGraph)
        else -> null
    }

}
