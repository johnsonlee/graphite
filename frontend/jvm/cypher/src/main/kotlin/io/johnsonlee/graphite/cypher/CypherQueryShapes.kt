package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node

internal const val MAX_ORDERED_TOP_K_ROWS = 10_000

/** Structural recognition only; each optimization must validate its own semantic restrictions. */
internal fun singleNodePattern(match: CypherClause.Match): PatternElement.NodePattern? =
    match.patterns.singleOrNull()?.takeIf { it.pathVariable == null }?.elements?.singleOrNull() as? PatternElement.NodePattern

private const val ORDERED_PROJECTION_CLAUSE_COUNT = 4
private const val ORDERED_PROJECTION_LIMIT_INDEX = 3

internal data class OrderedNodeProjection(
    val match: CypherClause.Match,
    val node: PatternElement.NodePattern,
    val returned: CypherClause.Return,
    val orderBy: CypherClause.OrderBy,
    val limit: CypherClause.Limit
)

internal fun orderedNodeProjection(clauses: List<CypherClause>): OrderedNodeProjection? {
    if (clauses.size != ORDERED_PROJECTION_CLAUSE_COUNT) return null
    val match = clauses[0] as? CypherClause.Match
    val returned = clauses[1] as? CypherClause.Return
    val orderBy = clauses[2] as? CypherClause.OrderBy
    val limit = clauses[ORDERED_PROJECTION_LIMIT_INDEX] as? CypherClause.Limit
    return when {
        match == null || returned == null -> null
        orderBy == null || limit == null -> null
        else -> singleNodePattern(match)?.let { OrderedNodeProjection(match, it, returned, orderBy, limit) }
    }
}

private const val FILTERED_QUERY_PREFIX_SIZE = 3

internal data class FilteredNodeQuery(
    val match: CypherClause.Match,
    val node: PatternElement.NodePattern,
    val where: CypherClause.Where,
    val returned: CypherClause.Return
)

/** Recognizes the MATCH/WHERE/RETURN prefix; callers decide which suffixes and semantics are supported. */
internal fun filteredNodeQuery(clauses: List<CypherClause>): FilteredNodeQuery? {
    if (clauses.size < FILTERED_QUERY_PREFIX_SIZE) return null
    val match = clauses[0] as? CypherClause.Match
    val where = clauses[1] as? CypherClause.Where
    val returned = clauses[2] as? CypherClause.Return
    return if (match != null && where != null && returned != null) {
        singleNodePattern(match)?.let { FilteredNodeQuery(match, it, where, returned) }
    } else null
}

internal data class FilteredPatternQuery(
    val match: CypherClause.Match,
    val where: CypherClause.Where,
    val returned: CypherClause.Return
)

internal fun filteredPatternQuery(clauses: List<CypherClause>): FilteredPatternQuery? {
    if (clauses.size < FILTERED_QUERY_PREFIX_SIZE) return null
    val match = clauses[0] as? CypherClause.Match
    val where = clauses[1] as? CypherClause.Where
    val returned = clauses[2] as? CypherClause.Return
    return if (match != null && where != null && returned != null) FilteredPatternQuery(match, where, returned) else null
}

private const val LIMITED_PATTERN_CLAUSE_COUNT = 3

internal data class LimitedPatternQuery(
    val match: CypherClause.Match,
    val returned: CypherClause.Return,
    val limit: CypherClause.Limit
)

internal fun limitedPatternQuery(clauses: List<CypherClause>): LimitedPatternQuery? {
    if (clauses.size != LIMITED_PATTERN_CLAUSE_COUNT) return null
    val match = clauses[0] as? CypherClause.Match
    val returned = clauses[1] as? CypherClause.Return
    val limit = clauses[2] as? CypherClause.Limit
    return if (match != null && returned != null && limit != null) LimitedPatternQuery(match, returned, limit) else null
}


internal fun resolveNodeClass(labels: List<String>): Class<out Node>? =
    labels.firstOrNull()?.let(NodePropertyAccessor::resolveNodeLabelOrNull)
        ?: Node::class.java.takeIf { labels.isEmpty() }

internal fun Number.toCypherInt(): Int = toLong()
    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
    .toInt()

// ========================================================================
// Pattern variable extraction
// ========================================================================

/**
 * Extract all variable names bound by a pattern.
 */
fun CypherPattern.variables(): Set<String> {
    val vars = mutableSetOf<String>()
    pathVariable?.let { vars.add(it) }
    for (element in elements) {
        when (element) {
            is PatternElement.NodePattern -> element.variable?.let { vars.add(it) }
            is PatternElement.RelationshipPattern -> element.variable?.let { vars.add(it) }
        }
    }
    return vars
}
