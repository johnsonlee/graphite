package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph

/** Evaluate a direct property's receiver once, including the ordinary-property fallback. */
internal inline fun isPropertyPresent(
    expression: CypherExpr,
    bindings: Map<String, Any?>,
    evaluate: (CypherExpr, Map<String, Any?>) -> Any?,
    resolveProperty: (Any?, String) -> Any?,
    graph: Graph?
): Boolean {
    if (expression !is CypherExpr.Property) return evaluate(expression, bindings) != null
    val receiver = evaluate(expression.expression, bindings)
    val property = expression.propertyName
    val declaredPresence = when (receiver) {
        is Node -> DeclaredTypeProperties.nodePropertyPresent(receiver, property, graph)
        is QualifiedNode -> DeclaredTypeProperties.nodePropertyPresent(receiver.node, property, receiver.graph)
        is MethodValue -> DeclaredTypeProperties.methodPropertyPresent(receiver.method, property, receiver.graph)
        else -> null
    }
    return declaredPresence ?: (resolveProperty(receiver, property) != null)
}
