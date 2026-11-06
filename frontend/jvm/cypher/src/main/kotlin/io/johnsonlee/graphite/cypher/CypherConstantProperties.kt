package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.NullConstant

internal fun constantProperty(node: ConstantNode, property: String): Any? =
    if (node is NullConstant || property != "value") null else node.value

internal fun constantProperties(node: ConstantNode): Map<String, Any?> = mapOf("id" to node.id.value, "value" to node.value)
