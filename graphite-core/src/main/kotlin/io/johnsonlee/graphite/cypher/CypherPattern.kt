package io.johnsonlee.graphite.cypher

/**
 * AST for Cypher graph patterns.
 *
 * A pattern is a chain of alternating [PatternElement.NodePattern] and
 * [PatternElement.RelationshipPattern] elements representing a path through
 * the graph.
 */
data class CypherPattern(val elements: List<PatternElement>)

/**
 * An element of a graph pattern -- either a node or a relationship.
 */
sealed class PatternElement {

    /**
     * A node pattern: `(variable:Label1:Label2 {key: value, ...})`
     */
    data class NodePattern(
        val variable: String? = null,
        val labels: List<String> = emptyList(),
        val properties: Map<String, CypherExpr> = emptyMap()
    ) : PatternElement()

    /**
     * A relationship pattern: `-[variable:TYPE1|TYPE2 {key: value} *min..max]->`
     */
    data class RelationshipPattern(
        val variable: String? = null,
        val types: List<String> = emptyList(),
        val properties: Map<String, CypherExpr> = emptyMap(),
        val direction: Direction = Direction.OUTGOING,
        val minHops: Int? = null,
        val maxHops: Int? = null,
        val variableLength: Boolean = false
    ) : PatternElement()
}

/**
 * Direction of a relationship in a pattern.
 */
enum class Direction {
    OUTGOING,
    INCOMING,
    BOTH
}
