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
