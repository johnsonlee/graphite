package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.*

internal fun resolveNodeType(type: String?): Class<out Node> = when (type?.lowercase()) {
    "callsite", "callsitenode" -> CallSiteNode::class.java
    "constant", "constantnode" -> ConstantNode::class.java
    "field", "fieldnode" -> FieldNode::class.java
    "parameter", "parameternode" -> ParameterNode::class.java
    "return", "returnnode" -> ReturnNode::class.java
    "local", "localvariable" -> LocalVariable::class.java
    else -> Node::class.java
}

internal fun formatNode(node: Node): String = when (node) {
    is CallSiteNode -> "CallSite[${node.id}] ${node.caller.declaringClass.simpleName}.${node.caller.name} -> ${node.callee.declaringClass.simpleName}.${node.callee.name}"
    is IntConstant -> "IntConstant[${node.id}] = ${node.value}"
    is StringConstant -> "StringConstant[${node.id}] = \"${node.value}\""
    is EnumConstant -> "EnumConstant[${node.id}] = ${node.enumType.simpleName}.${node.enumName}"
    is LongConstant -> "LongConstant[${node.id}] = ${node.value}"
    is FloatConstant -> "FloatConstant[${node.id}] = ${node.value}"
    is DoubleConstant -> "DoubleConstant[${node.id}] = ${node.value}"
    is BooleanConstant -> "BooleanConstant[${node.id}] = ${node.value}"
    is NullConstant -> "NullConstant[${node.id}]"
    is FieldNode -> "Field[${node.id}] ${node.descriptor.declaringClass.simpleName}.${node.descriptor.name}: ${node.descriptor.type.simpleName}"
    is ParameterNode -> "Parameter[${node.id}] ${node.method.name}#${node.index}: ${node.type.simpleName}"
    is ReturnNode -> "Return[${node.id}] ${node.method.name}"
    is LocalVariable -> "Local[${node.id}] ${node.name}: ${node.type.simpleName}"
}

internal fun nodeToMap(node: Node): Map<String, Any?> = when (node) {
    is CallSiteNode -> mapOf("type" to "CallSiteNode", "id" to node.id.value, "caller" to node.caller.signature, "callee" to node.callee.signature, "label" to "${node.callee.declaringClass.simpleName}.${node.callee.name}")
    is IntConstant -> mapOf("type" to "IntConstant", "id" to node.id.value, "value" to node.value, "label" to "${node.value}")
    is StringConstant -> mapOf("type" to "StringConstant", "id" to node.id.value, "value" to node.value, "label" to "\"${node.value}\"")
    is EnumConstant -> mapOf("type" to "EnumConstant", "id" to node.id.value, "enumType" to node.enumType.className, "enumName" to node.enumName, "label" to "${node.enumType.simpleName}.${node.enumName}")
    is LongConstant -> mapOf("type" to "LongConstant", "id" to node.id.value, "value" to node.value, "label" to "${node.value}L")
    is FloatConstant -> mapOf("type" to "FloatConstant", "id" to node.id.value, "value" to node.value, "label" to "${node.value}f")
    is DoubleConstant -> mapOf("type" to "DoubleConstant", "id" to node.id.value, "value" to node.value, "label" to "${node.value}d")
    is BooleanConstant -> mapOf("type" to "BooleanConstant", "id" to node.id.value, "value" to node.value, "label" to "${node.value}")
    is NullConstant -> mapOf("type" to "NullConstant", "id" to node.id.value, "label" to "null")
    is FieldNode -> mapOf("type" to "FieldNode", "id" to node.id.value, "class" to node.descriptor.declaringClass.className, "name" to node.descriptor.name, "fieldType" to node.descriptor.type.className, "label" to "${node.descriptor.declaringClass.simpleName}.${node.descriptor.name}")
    is ParameterNode -> mapOf("type" to "ParameterNode", "id" to node.id.value, "index" to node.index, "paramType" to node.type.className, "method" to node.method.signature, "label" to "param#${node.index}")
    is ReturnNode -> mapOf("type" to "ReturnNode", "id" to node.id.value, "method" to node.method.signature, "label" to "return")
    is LocalVariable -> mapOf("type" to "LocalVariable", "id" to node.id.value, "name" to node.name, "varType" to node.type.className, "method" to node.method.signature, "label" to node.name)
}

internal fun edgeToMap(edge: Edge): Map<String, Any?> = when (edge) {
    is DataFlowEdge -> mapOf("from" to edge.from.value, "to" to edge.to.value, "type" to "DataFlow", "kind" to edge.kind.name)
    is CallEdge -> mapOf("from" to edge.from.value, "to" to edge.to.value, "type" to "Call", "virtual" to edge.isVirtual, "dynamic" to edge.isDynamic)
    is TypeEdge -> mapOf("from" to edge.from.value, "to" to edge.to.value, "type" to "Type", "kind" to edge.kind.name)
    is ControlFlowEdge -> mapOf("from" to edge.from.value, "to" to edge.to.value, "type" to "ControlFlow", "kind" to edge.kind.name)
}
