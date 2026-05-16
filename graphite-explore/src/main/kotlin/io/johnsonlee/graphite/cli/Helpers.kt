package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.ValueNode

internal fun resolveNodeType(type: String?): Class<out Node> = when (type?.lowercase()) {
    "callsite", "callsitenode" -> CallSiteNode::class.java
    "constant", "constantnode" -> ConstantNode::class.java
    "resourcefile", "resourcefilenode" -> ResourceFileNode::class.java
    "resource", "resourcevalue", "resourcevaluenode" -> ResourceValueNode::class.java
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
    is ResourceFileNode -> "ResourceFile[${node.id}] ${node.path} (${node.source})"
    is ResourceValueNode -> "ResourceValue[${node.id}] ${node.path}#${node.key} = ${node.value}"
    is FieldNode -> "Field[${node.id}] ${node.descriptor.declaringClass.simpleName}.${node.descriptor.name}: ${node.descriptor.type.simpleName}"
    is ParameterNode -> "Parameter[${node.id}] ${node.method.name}#${node.index}: ${node.type.simpleName}"
    is ReturnNode -> "Return[${node.id}] ${node.method.name}"
    is LocalVariable -> "Local[${node.id}] ${node.name}: ${node.type.simpleName}"
    is AnnotationNode -> "Annotation[${node.id}] @${node.name.substringAfterLast('.')} on ${node.className.substringAfterLast('.')}.${node.memberName}"
}

internal fun nodeToMap(node: Node): Map<String, Any?> = when (node) {
    is CallSiteNode -> mapOf(
        API_FIELD_TYPE to "CallSiteNode",
        API_FIELD_ID to node.id.value,
        "caller" to node.caller.signature,
        "callee" to node.callee.signature,
        API_FIELD_LABEL to "${node.callee.declaringClass.simpleName}.${node.callee.name}"
    )
    is IntConstant -> mapOf(
        API_FIELD_TYPE to "IntConstant",
        API_FIELD_ID to node.id.value,
        API_FIELD_VALUE to node.value,
        API_FIELD_LABEL to "${node.value}"
    )
    is StringConstant -> mapOf(
        API_FIELD_TYPE to "StringConstant",
        API_FIELD_ID to node.id.value,
        API_FIELD_VALUE to node.value,
        API_FIELD_LABEL to "\"${node.value}\""
    )
    is EnumConstant -> mapOf(
        API_FIELD_TYPE to "EnumConstant",
        API_FIELD_ID to node.id.value,
        "enumType" to node.enumType.className,
        "enumName" to node.enumName,
        API_FIELD_LABEL to "${node.enumType.simpleName}.${node.enumName}"
    )
    is LongConstant -> mapOf(
        API_FIELD_TYPE to "LongConstant",
        API_FIELD_ID to node.id.value,
        API_FIELD_VALUE to node.value,
        API_FIELD_LABEL to "${node.value}L"
    )
    is FloatConstant -> mapOf(
        API_FIELD_TYPE to "FloatConstant",
        API_FIELD_ID to node.id.value,
        API_FIELD_VALUE to node.value,
        API_FIELD_LABEL to "${node.value}f"
    )
    is DoubleConstant -> mapOf(
        API_FIELD_TYPE to "DoubleConstant",
        API_FIELD_ID to node.id.value,
        API_FIELD_VALUE to node.value,
        API_FIELD_LABEL to "${node.value}d"
    )
    is BooleanConstant -> mapOf(
        API_FIELD_TYPE to "BooleanConstant",
        API_FIELD_ID to node.id.value,
        API_FIELD_VALUE to node.value,
        API_FIELD_LABEL to "${node.value}"
    )
    is NullConstant -> mapOf(API_FIELD_TYPE to "NullConstant", API_FIELD_ID to node.id.value, API_FIELD_LABEL to "null")
    is ResourceFileNode -> mapOf(
        API_FIELD_TYPE to "ResourceFileNode",
        API_FIELD_ID to node.id.value,
        API_FIELD_PATH to node.path,
        API_FIELD_SOURCE to node.source,
        API_FIELD_FORMAT to node.format,
        API_FIELD_PROFILE to node.profile,
        API_FIELD_LABEL to node.path
    )
    is ResourceValueNode -> mapOf(
        API_FIELD_TYPE to "ResourceValueNode",
        API_FIELD_ID to node.id.value,
        API_FIELD_PATH to node.path,
        "key" to node.key,
        API_FIELD_VALUE to node.value,
        API_FIELD_FORMAT to node.format,
        API_FIELD_PROFILE to node.profile,
        API_FIELD_LABEL to "${node.key}=${node.value}"
    )
    is FieldNode -> mapOf(
        API_FIELD_TYPE to "FieldNode",
        API_FIELD_ID to node.id.value,
        API_FIELD_CLASS to node.descriptor.declaringClass.className,
        API_FIELD_NAME to node.descriptor.name,
        "fieldType" to node.descriptor.type.className,
        API_FIELD_LABEL to "${node.descriptor.declaringClass.simpleName}.${node.descriptor.name}"
    )
    is ParameterNode -> mapOf(
        API_FIELD_TYPE to "ParameterNode",
        API_FIELD_ID to node.id.value,
        "index" to node.index,
        "paramType" to node.type.className,
        API_FIELD_METHOD to node.method.signature,
        API_FIELD_LABEL to "param#${node.index}"
    )
    is ReturnNode -> mapOf(
        API_FIELD_TYPE to "ReturnNode",
        API_FIELD_ID to node.id.value,
        API_FIELD_METHOD to node.method.signature,
        API_FIELD_LABEL to "return"
    )
    is LocalVariable -> mapOf(
        API_FIELD_TYPE to "LocalVariable",
        API_FIELD_ID to node.id.value,
        API_FIELD_NAME to node.name,
        "varType" to node.type.className,
        API_FIELD_METHOD to node.method.signature,
        API_FIELD_LABEL to node.name
    )
    is AnnotationNode -> mutableMapOf<String, Any?>(
        API_FIELD_TYPE to "AnnotationNode",
        API_FIELD_ID to node.id.value,
        API_FIELD_NAME to node.name,
        API_FIELD_CLASS to node.className,
        "member" to node.memberName,
        API_FIELD_LABEL to "@${node.name.substringAfterLast('.')}"
    ).also { map -> node.values.forEach { (k, v) -> map[k] = v } }
}

internal fun edgeToMap(edge: Edge): Map<String, Any?> = when (edge) {
    is DataFlowEdge -> mapOf(
        "from" to edge.from.value,
        "to" to edge.to.value,
        API_FIELD_TYPE to "DataFlow",
        API_FIELD_KIND to edge.kind.name
    )
    is CallEdge -> mapOf(
        "from" to edge.from.value,
        "to" to edge.to.value,
        API_FIELD_TYPE to "Call",
        API_FIELD_VIRTUAL to edge.isVirtual,
        API_FIELD_DYNAMIC to edge.isDynamic
    )
    is TypeEdge -> mapOf(
        "from" to edge.from.value,
        "to" to edge.to.value,
        API_FIELD_TYPE to "Type",
        API_FIELD_KIND to edge.kind.name
    )
    is ControlFlowEdge -> mapOf(
        "from" to edge.from.value,
        "to" to edge.to.value,
        API_FIELD_TYPE to "ControlFlow",
        API_FIELD_KIND to edge.kind.name
    )
    is ResourceEdge -> mapOf(
        "from" to edge.from.value,
        "to" to edge.to.value,
        API_FIELD_TYPE to "Resource",
        API_FIELD_KIND to edge.kind.name
    )
}
