package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.Graph

/**
 * Execute a Cypher query against this graph.
 *
 * This is an extension function provided by the `graphite-cypher` module.
 * Add `graphite-cypher` to your dependencies to use it.
 */
fun Graph.query(cypher: String): CypherResult {
    return CypherExecutor(this).execute(cypher)
}

/** Execute a parameterized Cypher query against this graph. */
fun Graph.query(cypher: String, parameters: Map<String, Any?>): CypherResult {
    return CypherExecutor(this).execute(cypher, parameters)
}
