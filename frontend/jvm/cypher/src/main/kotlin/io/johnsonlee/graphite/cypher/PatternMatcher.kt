package io.johnsonlee.graphite.cypher



/** Coordinates connected patterns, optional rows and exact path bindings. */
internal class PatternMatcher(
    private val sources: List<CypherGraph>,
    private val context: PipelineExecutionContext,
    private val rowOperations: CypherRowOperations,
    private val pathState: PatternPathState,
    private val nodeMatcher: NodePatternMatcher,
    private val relationshipMatcher: RelationshipPatternMatcher
) {
    fun executeMatch(
        patterns: List<CypherPattern>,
        inputRows: List<Map<String, Any?>>,
        limit: Int? = null
    ): List<Map<String, Any?>> {
        var rows = if (patterns.size > 1) inputRows.map(pathState::startRelationshipMatchState) else inputRows
        for (pattern in patterns) {
            val nextRows = mutableListOf<Map<String, Any?>>()
            for (inputRow in rows) {
                nextRows.addAll(matchPattern(pattern, inputRow, limit))
                if (limit != null && nextRows.size >= limit) break
            }
            rows = if (limit != null && nextRows.size > limit) nextRows.subList(0, limit) else nextRows
        }
        return rows.map(pathState::removeRelationshipMatchState)
    }

    fun executeOptionalMatch(
        patterns: List<CypherPattern>,
        where: CypherExpr?,
        inputRows: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        for (inputRow in inputRows) {
            var currentMatches = listOf(
                if (patterns.size > 1) pathState.startRelationshipMatchState(inputRow) else inputRow
            )

            for (pattern in patterns) {
                val nextMatches = mutableListOf<Map<String, Any?>>()
                for (row in currentMatches) {
                    nextMatches.addAll(matchPattern(pattern, row))
                }
                currentMatches = nextMatches
            }

            // WHERE belongs to the OPTIONAL MATCH operation. A row for which
            // the pattern matched but the predicate did not is therefore an
            // unsuccessful optional match and must be preserved with only the
            // newly introduced variables set to null.
            if (where != null) {
                currentMatches = currentMatches.filter { context.evaluator.evaluate(where, it) == true }
            }

            if (currentMatches.isEmpty()) {
                // Optional match: null only NEW variables introduced by this pattern,
                // not variables already bound in inputRow
                val newVars = patterns.flatMap { it.variables() }
                    .filter { it !in inputRow }
                val nullBindings = newVars.associateWith { null as Any? }
                results.add(inputRow + nullBindings)
            } else {
                results.addAll(currentMatches.map(pathState::removeRelationshipMatchState))
            }
        }

        return results
    }

    /**
     * Match a single [CypherPattern] (which is a chain of node/relationship elements)
     * against the graph, extending the given bindings.
     */
    fun matchPattern(
        pattern: CypherPattern,
        existingBindings: Map<String, Any?>,
        limit: Int? = null
    ): List<Map<String, Any?>> {
        val elements = pattern.elements
        if (elements.isEmpty()) return listOf(existingBindings)
        val relationshipCount = elements.count { it is PatternElement.RelationshipPattern }
        val trackSegments = pattern.pathVariable != null || relationshipCount > 1
        val matches = if (limit != null) {
            matchPatternLazily(
                pattern,
                existingBindings,
                trackSegments = trackSegments
            ).take(limit).toList()
        } else {
            val initialBindings = pathState.prepareRelationshipMatchState(pattern, existingBindings)
            matchPatternEagerly(
                elements,
                initialBindings,
                limit = null,
                trackSegments = trackSegments
            )
        }

        return if (pattern.pathVariable == null && matches.none(pathState::containsInternalPathState)) {
            matches
        } else matches.map { bindings ->
            val path = pattern.pathVariable?.let { pathState.buildPathRepresentation(pattern, bindings) }
            bindings.toMutableMap().apply {
                remove(INTERNAL_MATCHED_PATH_SEGMENTS_KEY)
                remove(INTERNAL_CURRENT_NODE_KEY)
                remove(INTERNAL_PATH_START_NODE_KEY)
                if (pattern.pathVariable != null) {
                    this[pattern.pathVariable] = path
                    rowOperations.addProvenance(this, path)
                }
            }
        }
    }

    fun matchPatternLazily(
        pattern: CypherPattern,
        existingBindings: Map<String, Any?>,
        candidateSources: List<CypherGraph> = sources,
        finalResultPredicate: ((Map<String, Any?>) -> Boolean)? = null,
        seedPredicate: ((Map<String, Any?>) -> Boolean)? = null,
        seedMatches: Sequence<Map<String, Any?>>? = null,
        trackSegments: Boolean = pattern.pathVariable != null ||
            pattern.elements.count { it is PatternElement.RelationshipPattern } > 1
    ): Sequence<Map<String, Any?>> {
        val elements = pattern.elements
        if (elements.isEmpty()) return sequenceOf(existingBindings)
        val initialBindings = pathState.prepareRelationshipMatchState(pattern, existingBindings)

        var currentMatches: Sequence<Map<String, Any?>> = seedMatches ?: nodeMatcher.matchNodeElementLazily(
            elements[0] as PatternElement.NodePattern,
            initialBindings,
            candidateSources,
            navigate = elements.size > 1,
            trackSegments = trackSegments
        )
        if (seedPredicate != null) currentMatches = currentMatches.filter(seedPredicate)
        var i = 1
        while (i < elements.size) {
            val sourceNode = elements[i - 1] as PatternElement.NodePattern
            val rel = elements[i] as PatternElement.RelationshipPattern
            val targetNode = elements[i + 1] as PatternElement.NodePattern
            val resultPredicate = finalResultPredicate.takeIf { i + 1 == elements.lastIndex }
            currentMatches = currentMatches.flatMap { bindings ->
                relationshipMatcher.matchRelationshipLazily(sourceNode, rel, targetNode, bindings, resultPredicate)
            }
            i += 2
        }
        return currentMatches
    }

    private fun matchPatternEagerly(
        elements: List<PatternElement>,
        existingBindings: Map<String, Any?>,
        limit: Int?,
        trackSegments: Boolean
    ): List<Map<String, Any?>> {
        var currentMatches = nodeMatcher.matchNodeElement(
            elements[0] as PatternElement.NodePattern,
            existingBindings,
            limit,
            navigate = elements.size > 1,
            trackSegments = trackSegments
        )

        var i = 1
        while (i < elements.size) {
            val sourceNode = elements[i - 1] as PatternElement.NodePattern
            val rel = elements[i] as PatternElement.RelationshipPattern
            val targetNode = elements[i + 1] as PatternElement.NodePattern
            i += 2

            val nextMatches = mutableListOf<Map<String, Any?>>()
            for (bindings in currentMatches) {
                nextMatches.addAll(relationshipMatcher.matchRelationship(sourceNode, rel, targetNode, bindings, limit = null))
            }
            currentMatches = nextMatches
        }

        return currentMatches
    }
}
