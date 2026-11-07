package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringPropertyLookup
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.TransformedStringPropertyLookup

private const val SINGLE_HOP_PATTERN_ELEMENTS = 3

/** Compiles safe seed plans separately from their lazy source and target scans. */
internal class FilteredMatchSeeds(
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations,
    private val nodeMatcher: NodePatternMatcher,
    private val stringScanner: DirectStringScanner
) {
    private data class TargetShape(
        val source: PatternElement.NodePattern,
        val relationship: PatternElement.RelationshipPattern,
        val target: PatternElement.NodePattern,
        val targetVariable: String
    ) {
        fun eligible(): Boolean = !relationship.variableLength && hasNoInlineConstraints() && hasIndependentBindings()

        private fun hasNoInlineConstraints(): Boolean =
            source.properties.isEmpty() && relationship.properties.isEmpty() && target.properties.isEmpty()

        private fun hasIndependentBindings(): Boolean {
            val variables = listOfNotNull(source.variable, target.variable)
            return variables.size == 2 && variables.distinct().size == 2 && relationship.variable !in variables
        }
    }

    private data class TargetRequest(val shape: TargetShape, val nodeClass: Class<out Node>, val plan: DirectStringCandidatePlan)

    private data class NodeRequest(val variable: String, val nodeClass: Class<out Node>, val plan: DirectStringCandidatePlan)

    private sealed interface DirectRequest {
        data object Unavailable : DirectRequest

        data object Empty : DirectRequest

        data class Ready(val node: NodeRequest) : DirectRequest
    }

    /** Defer target absence detection until the first source fails to satisfy the caller. */
    fun targetPreflightSourceMatches(
        pattern: CypherPattern,
        condition: CypherExpr,
        candidateSources: List<CypherGraph>
    ): Sequence<Map<String, Any?>>? =
        compileTargetRequest(pattern, condition)?.let { request ->
            candidateSources.asSequence().flatMap { source -> targetSourceMatches(source, request) }
        }

    private fun targetShape(pattern: CypherPattern): TargetShape? {
        val elements = pattern.elements.takeIf { it.size == SINGLE_HOP_PATTERN_ELEMENTS && pattern.pathVariable == null }
        val source = elements?.get(0) as? PatternElement.NodePattern
        val relationship = elements?.get(1) as? PatternElement.RelationshipPattern
        val target = elements?.get(2) as? PatternElement.NodePattern
        return when {
            source == null || relationship == null || target?.variable == null -> null
            else -> TargetShape(source, relationship, target, target.variable).takeIf { it.eligible() }
        }
    }

    private fun compileTargetRequest(pattern: CypherPattern, condition: CypherExpr): TargetRequest? =
        targetShape(pattern)?.let { shape ->
            val targetCondition = SourcePredicatePushdown.compile(condition, shape.targetVariable)
            val plan = targetCondition?.let { DirectStringCandidatePlan.compile(it, shape.targetVariable, context.parameters.orEmpty()) }
            plan?.let { compiled -> resolveNodeClass(shape.target.labels)?.let { TargetRequest(shape, it, compiled) } }
        }

    private fun targetSourceMatches(source: CypherGraph, request: TargetRequest): Sequence<Map<String, Any?>> {
        val matches =
            nodeMatcher.matchNodeElementLazily(request.shape.source, emptyMap(), listOf(source), navigate = true, trackSegments = false)
        return if (canLookupTarget(source.graph, request.plan)) deferTargetProbe(source, request, matches) else matches
    }

    private fun canLookupTarget(graph: Graph, plan: DirectStringCandidatePlan): Boolean {
        val hasOrderedLookup = graph is StringPropertyLookup && graph is StringPropertyLookupOrder
        val canTransform = plan.candidates.filters.none { it.transform != null } || graph is TransformedStringPropertyLookup
        return hasOrderedLookup && canTransform
    }

    private fun deferTargetProbe(
        source: CypherGraph,
        request: TargetRequest,
        matches: Sequence<Map<String, Any?>>
    ): Sequence<Map<String, Any?>> = sequence {
        val iterator = matches.iterator()
        if (iterator.hasNext()) {
            // Let a high-degree first source satisfy LIMIT before paying for a target lookup.
            yield(iterator.next())
            if (iterator.hasNext() && targetExists(source, request)) yieldAll(iterator)
        }
    }

    private fun targetExists(source: CypherGraph, request: TargetRequest): Boolean =
        stringScanner
            .directStringCandidates(graph = source.graph, nodeClass = request.nodeClass, disjunction = request.plan.candidates, limit = 1)
            .iterator()
            .hasNext()

    fun orderedSourceMatches(
        node: PatternElement.NodePattern,
        condition: CypherExpr,
        candidateSources: List<CypherGraph>
    ): Sequence<Map<String, Any?>>? =
        if (node.properties.isNotEmpty() || candidateSources.any { it.graph !is StringPropertyLookupOrder }) {
            null
        } else {
            compileNodeRequest(node, condition)?.let { request -> orderedBindings(node, request, candidateSources) }
        }

    private fun compileNodeRequest(node: PatternElement.NodePattern, condition: CypherExpr): NodeRequest? =
        node.variable?.let { variable ->
            resolveNodeClass(node.labels)?.let { nodeClass ->
                DirectStringCandidatePlan.compile(condition, variable, context.parameters.orEmpty())?.let {
                    NodeRequest(variable, nodeClass, it)
                }
            }
        }

    private fun orderedBindings(
        node: PatternElement.NodePattern,
        request: NodeRequest,
        candidateSources: List<CypherGraph>
    ): Sequence<Map<String, Any?>> =
        candidateSources.asSequence().flatMap { source ->
            // Never limit seeds: final WHERE may reject all relationships from a matching node.
            stringScanner
                .directStringCandidates(
                    graph = source.graph,
                    nodeClass = request.nodeClass,
                    disjunction = request.plan.candidates,
                    storage = StringStorageOptions(graphAccess.sources.size, false, true, false))
                .mapNotNull { candidate ->
                    nodeMatcher.bindNodeCandidate(
                        graphAccess.nodeValue(source, candidate), node, emptyMap(), navigate = true, trackSegments = false)
                }
        }

    fun directStringBindings(
        nodePattern: PatternElement.NodePattern,
        condition: CypherExpr,
        candidateSources: List<CypherGraph>
    ): Sequence<Map<String, Any?>>? =
        when (val request = compileDirectRequest(nodePattern, condition)) {
            DirectRequest.Unavailable -> null
            DirectRequest.Empty -> emptySequence()
            is DirectRequest.Ready -> directBindings(request.node, candidateSources)
        }

    private fun compileDirectRequest(node: PatternElement.NodePattern, condition: CypherExpr): DirectRequest {
        val variable = node.variable
        return when {
            node.labels.size > 1 || node.properties.isNotEmpty() -> DirectRequest.Unavailable
            variable == null -> DirectRequest.Unavailable
            else ->
                resolveNodeClass(node.labels)?.let { nodeClass ->
                    val plan = DirectStringCandidatePlan.compile(condition, variable, context.parameters.orEmpty())
                    plan?.let { DirectRequest.Ready(NodeRequest(variable, nodeClass, it)) } ?: DirectRequest.Unavailable
                } ?: DirectRequest.Empty
        }
    }

    private fun directBindings(request: NodeRequest, candidateSources: List<CypherGraph>): Sequence<Map<String, Any?>> =
        candidateSources.asSequence().flatMap { source ->
            stringScanner
                .directStringCandidates(graph = source.graph, nodeClass = request.nodeClass, disjunction = request.plan.candidates)
                .map { node ->
                    mutableMapOf<String, Any?>(request.variable to graphAccess.nodeValue(source, node)).also { bindings ->
                        rowOperations.addProvenance(bindings, bindings[request.variable])
                    }
                }
        }
}
