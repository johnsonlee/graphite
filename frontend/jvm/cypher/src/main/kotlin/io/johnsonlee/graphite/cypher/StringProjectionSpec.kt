package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node

/** The immutable binding and output shape of a direct storage string projection. */
internal data class StringProjectionSpec(
    val nodeClass: Class<out Node>,
    val variable: String,
    val filter: DirectStringDisjunction,
    val items: List<ReturnItem>,
    val columns: List<String>
)

internal typealias DirectNodePredicateFactory = (CypherGraph) -> (Node) -> Boolean
