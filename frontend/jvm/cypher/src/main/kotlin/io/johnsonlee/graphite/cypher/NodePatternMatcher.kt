package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId

/** Node candidate binding and inline label/property constraints. */
internal class NodePatternMatcher(
    private val sources: List<CypherGraph>,
    private val qualified: Boolean,
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations
) {
    /**
     * Match a [PatternElement.NodePattern] against the graph.
     */
    fun matchNodeElement(
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>,
        limit: Int? = null,
        navigate: Boolean,
        trackSegments: Boolean
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for (candidate in nodeElementCandidates(nodePattern, existingBindings)) {
            val bindings = bindNodeCandidate(
                candidate,
                nodePattern,
                existingBindings,
                navigate,
                trackSegments
            )
            if (bindings != null) {
                results.add(bindings)
                if (limit != null && results.size >= limit) break
            }
        }
        return results
    }

    fun matchNodeElementLazily(
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>,
        candidateSources: List<CypherGraph> = sources,
        navigate: Boolean,
        trackSegments: Boolean
    ): Sequence<Map<String, Any?>> = nodeElementCandidates(nodePattern, existingBindings, candidateSources)
        .mapNotNull { candidate ->
            bindNodeCandidate(
                candidate,
                nodePattern,
                existingBindings,
                navigate = navigate,
                trackSegments = trackSegments
            )
        }

    fun nodeElementCandidates(
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>,
        candidateSources: List<CypherGraph> = sources
    ): Sequence<Any> {
        val methodSource = nodePattern.labels.any(MethodQueryExecutor::isMethodLabel)
        val nodeClass = if (methodSource) Node::class.java else resolveNodeClass(nodePattern.labels)
            ?: return emptySequence()

        return if (nodePattern.variable != null &&
            existingBindings.containsKey(nodePattern.variable)
        ) {
            val existing = existingBindings[nodePattern.variable]
            val matchesSource = if (methodSource) {
                existing is MethodValue
            } else {
                nodeValue(existing)?.let(nodeClass::isInstance) == true
            }
            if (existing != null && matchesSource) {
                sequenceOf(existing)
            } else {
                emptySequence()
            }
        } else if (methodSource) {
            graphAccess.methodCandidates()
        } else {
            graphAccess.nodeCandidates(nodeClass, candidateSources)
        }
    }

    /** Bind selected IDs through the ordinary single-node matcher; callers retain the full WHERE. */
    fun qualifiedIdBindings(
        pattern: CypherPattern,
        condition: CypherExpr,
        candidateSources: List<CypherGraph>
    ): Sequence<Map<String, Any?>>? {
        if (!qualified || pattern.pathVariable != null) return null
        return (pattern.elements.singleOrNull() as? PatternElement.NodePattern)
            ?.takeIf { it.properties.isEmpty() }
            ?.let { node ->
                qualifiedIdCandidates(node, condition, candidateSources)?.mapNotNull { candidate ->
                    bindNodeCandidate(candidate, node, emptyMap(), navigate = false, trackSegments = false)
                }
            }
    }

    private fun qualifiedIdCandidates(
        node: PatternElement.NodePattern,
        condition: CypherExpr,
        candidateSources: List<CypherGraph>
    ): Sequence<Any>? {
        val variable = node.variable ?: return null
        return resolveNodeClass(node.labels)?.let { nodeClass ->
            graphAccess.qualifiedIdCandidates(nodeClass, condition, variable, candidateSources)
        }
    }

    fun bindNodeCandidate(
        candidate: Any,
        nodePattern: PatternElement.NodePattern,
        existingBindings: Map<String, Any?>,
        navigate: Boolean,
        trackSegments: Boolean
    ): Map<String, Any?>? {
        if (!matchesNodeConstraints(candidate, nodePattern, existingBindings)) return null
        return existingBindings.toMutableMap().apply {
            if (trackSegments) putIfAbsent(INTERNAL_PATH_START_NODE_KEY, candidate)
            if (navigate) this[INTERNAL_CURRENT_NODE_KEY] = candidate
            if (nodePattern.variable != null) {
                this[nodePattern.variable] = candidate
                rowOperations.addProvenance(this, candidate)
            }
        }
    }

    fun matchTargetNode(
        targetPattern: PatternElement.NodePattern,
        value: Any,
        bindings: Map<String, Any?>
    ): Map<String, Any?>? {
        val node = nodeValue(value) ?: return null
        val matchesClass = resolveNodeClass(targetPattern.labels)?.isInstance(node) == true
        val matchesBinding = targetPattern.variable == null || !bindings.containsKey(targetPattern.variable) ||
            bindings[targetPattern.variable] == value
        return if (matchesClass && matchesBinding && matchesNodeConstraints(value, targetPattern, bindings)) {
            bindings.toMutableMap().apply {
                this[INTERNAL_CURRENT_NODE_KEY] = value
                if (targetPattern.variable != null) {
                    this[targetPattern.variable] = value
                    rowOperations.addProvenance(this, value)
                }
            }
        } else {
            null
        }
    }

    fun matchesNodeConstraints(
        value: Any,
        pattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>
    ): Boolean {
        return if (value is MethodValue) {
            matchesMethodConstraints(value, pattern, bindings)
        } else {
            nodeValue(value)?.let { node ->
                matchesLabels(value, node, pattern.labels) && pattern.properties.all { (key, expression) ->
                    cypherEquals(graphAccess.nodeProperty(value, key), context.evaluator.evaluate(expression, bindings)) == true
                }
            } == true
        }
    }

    private fun matchesMethodConstraints(
        value: MethodValue,
        pattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>
    ): Boolean = pattern.labels.all(MethodQueryExecutor::isMethodLabel) && pattern.properties.all { (key, expression) ->
        value.property(key) == context.evaluator.evaluate(expression, bindings)
    }

    private fun matchesLabels(value: Any, node: Node, labels: List<String>): Boolean {
        if (labels.size <= 1) return true
        val nodeLabels = CypherFunctions.labels(value).map { it.lowercase() }.toSet()
        return labels.all { label ->
            NodePropertyAccessor.resolveNodeLabelOrNull(label)?.isInstance(node) == true || label.lowercase() in nodeLabels
        }
    }

    fun tryElementIdSeek(
        match: CypherClause.Match,
        where: CypherClause.Where,
        inputRows: List<Map<String, Any?>>
    ): List<Map<String, Any?>>? {
        if (match.optional) return null
        return singleNodePattern(match)?.let { node ->
            node.variable?.takeIf { variable -> inputRows.none { variable in it } }?.let { variable ->
                elementIdEquality(where.condition, variable)?.let { elementId -> seekBindings(node, variable, elementId, where, inputRows) }
            }
        }
    }

    private fun seekBindings(
        pattern: PatternElement.NodePattern,
        variable: String,
        elementId: String,
        where: CypherClause.Where,
        inputs: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val found = seekNode(elementId) ?: return emptyList()
        return resolveNodeClass(pattern.labels)?.takeIf { it.isInstance(found.second) }?.let {
            projectSeekBindings(found, pattern, variable, where, inputs)
        }.orEmpty()
    }

    private fun projectSeekBindings(
        found: Pair<CypherGraph, Node>,
        pattern: PatternElement.NodePattern,
        variable: String,
        where: CypherClause.Where,
        inputs: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val candidate = graphAccess.nodeValue(found.first, found.second)
        val rows = mutableListOf<Map<String, Any?>>()
        for (input in inputs) {
            if (!matchesNodeConstraints(candidate, pattern, input)) continue
            val bindings = input.toMutableMap()
            bindings[variable] = candidate
            rowOperations.addProvenance(bindings, candidate)
            if (context.evaluator.evaluate(where.condition, bindings) == true) rows.add(bindings)
        }
        return rows
    }
    fun elementIdEquality(expression: CypherExpr, variable: String): String? =
        (expression as? CypherExpr.Comparison)?.takeIf { it.op == "=" }?.let { comparison ->
            when {
                isElementIdReference(comparison.left, variable) -> (comparison.right as? CypherExpr.Literal)?.value as? String
                isElementIdReference(comparison.right, variable) -> (comparison.left as? CypherExpr.Literal)?.value as? String
                else -> null
            }
        }

    fun isElementIdReference(expression: CypherExpr, variable: String): Boolean = when (expression) {
        is CypherExpr.FunctionCall -> expression.name.equals("elementId", ignoreCase = true) &&
            expression.args.singleOrNull() == CypherExpr.Variable(variable)
        is CypherExpr.Property -> expression.expression == CypherExpr.Variable(variable) &&
            expression.propertyName in setOf(ELEMENT_ID_PROPERTY, QUALIFIED_ID_PROPERTY)
        else -> false
    }


    fun seekNode(elementId: String): Pair<CypherGraph, Node>? =
        if (qualified) seekQualifiedNode(elementId) else seekLocalNode(elementId)

    private fun seekLocalNode(elementId: String): Pair<CypherGraph, Node>? {
        val id = elementId.toIntOrNull() ?: return null
        val source = sources.single()
        return graphAccess.trackedNode(source.graph, NodeId(id))?.let { source to it }
    }

    private fun seekQualifiedNode(elementId: String): Pair<CypherGraph, Node>? {
        val separator = elementId.lastIndexOf(':')
        if (separator < 0 || separator == elementId.lastIndex) return null
        val graphId = elementId.substring(0, separator)
        return elementId.substring(separator + 1).toIntOrNull()?.let { id ->
            sources.firstOrNull { it.id == graphId }?.let { source ->
                graphAccess.trackedNode(source.graph, NodeId(id))?.let { source to it }
            }
        }
    }

}
