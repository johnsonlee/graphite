package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.NullConstant

/** Shared value semantics for primitive constants; enum-specific metadata stays with its caller. */
internal object CypherConstantProperties {
    fun getProperty(node: ConstantNode, property: String): Any? =
        if (node is NullConstant || property != "value") null else node.value

    fun getAllProperties(node: ConstantNode): Map<String, Any?> = mapOf("id" to node.id.value, "value" to node.value)
}
