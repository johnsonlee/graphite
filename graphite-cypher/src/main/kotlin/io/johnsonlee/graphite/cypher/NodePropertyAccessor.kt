package io.johnsonlee.graphite.cypher

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

/**
 * Resolves Cypher property names to actual values on Graphite nodes.
 */
object NodePropertyAccessor {
    private const val PROPERTY_ID = "id"
    private const val PROPERTY_TYPE = "type"
    private const val PROPERTY_VALUE = "value"
    private const val PROPERTY_NAME = "name"
    private const val PROPERTY_METHOD = "method"
    private const val PROPERTY_CLASS = "class"
    private const val PROPERTY_FORMAT = "format"
    private const val PROPERTY_PROFILE = "profile"

    fun getProperty(node: Node, property: String): Any? {
        // Check global properties first (except PROPERTY_TYPE which is ambiguous)
        if (property == PROPERTY_ID) return node.id.value

        // Try node-specific properties first (they take precedence)
        val nodeSpecific = when (node) {
            is CallSiteNode -> getCallSiteProperty(node, property)
            is IntConstant -> getIntConstantProperty(node, property)
            is StringConstant -> getStringConstantProperty(node, property)
            is LongConstant -> getLongConstantProperty(node, property)
            is FloatConstant -> getFloatConstantProperty(node, property)
            is DoubleConstant -> getDoubleConstantProperty(node, property)
            is BooleanConstant -> getBooleanConstantProperty(node, property)
            is NullConstant -> null
            is EnumConstant -> getEnumConstantProperty(node, property)
            is LocalVariable -> getLocalVariableProperty(node, property)
            is FieldNode -> getFieldNodeProperty(node, property)
            is ParameterNode -> getParameterNodeProperty(node, property)
            is ReturnNode -> getReturnNodeProperty(node, property)
            is ResourceFileNode -> getResourceFileNodeProperty(node, property)
            is ResourceValueNode -> getResourceValueNodeProperty(node, property)
            is AnnotationNode -> getAnnotationNodeProperty(node, property)
        }
        if (nodeSpecific != null) return nodeSpecific

        // Fall back to global properties
        return when (property) {
            PROPERTY_TYPE -> nodeTypeName(node)
            else -> null
        }
    }

    fun nodeTypeName(node: Node): String = when (node) {
        is CallSiteNode -> "CallSiteNode"
        is IntConstant -> "IntConstant"
        is StringConstant -> "StringConstant"
        is LongConstant -> "LongConstant"
        is FloatConstant -> "FloatConstant"
        is DoubleConstant -> "DoubleConstant"
        is BooleanConstant -> "BooleanConstant"
        is NullConstant -> "NullConstant"
        is EnumConstant -> "EnumConstant"
        is LocalVariable -> "LocalVariable"
        is FieldNode -> "FieldNode"
        is ParameterNode -> "ParameterNode"
        is ReturnNode -> "ReturnNode"
        is ResourceFileNode -> "ResourceFileNode"
        is ResourceValueNode -> "ResourceValueNode"
        is AnnotationNode -> "AnnotationNode"
    }

    private fun getCallSiteProperty(node: CallSiteNode, prop: String) = when (prop) {
        "callee_class" -> node.callee.declaringClass.className
        "callee_name" -> node.callee.name
        "callee_signature" -> node.callee.signature
        "caller_class" -> node.caller.declaringClass.className
        "caller_name" -> node.caller.name
        "caller_signature" -> node.caller.signature
        "line" -> node.lineNumber
        else -> null
    }

    private fun getIntConstantProperty(node: IntConstant, prop: String) = when (prop) {
        PROPERTY_VALUE -> node.value
        else -> null
    }

    private fun getStringConstantProperty(node: StringConstant, prop: String) = when (prop) {
        PROPERTY_VALUE -> node.value
        else -> null
    }

    private fun getLongConstantProperty(node: LongConstant, prop: String) = when (prop) {
        PROPERTY_VALUE -> node.value
        else -> null
    }

    private fun getFloatConstantProperty(node: FloatConstant, prop: String) = when (prop) {
        PROPERTY_VALUE -> node.value
        else -> null
    }

    private fun getDoubleConstantProperty(node: DoubleConstant, prop: String) = when (prop) {
        PROPERTY_VALUE -> node.value
        else -> null
    }

    private fun getBooleanConstantProperty(node: BooleanConstant, prop: String) = when (prop) {
        PROPERTY_VALUE -> node.value
        else -> null
    }

    private fun getEnumConstantProperty(node: EnumConstant, prop: String) = when (prop) {
        PROPERTY_VALUE -> node.value
        PROPERTY_NAME -> node.enumName
        "enum_type" -> node.enumType.className
        else -> null
    }

    private fun getLocalVariableProperty(node: LocalVariable, prop: String) = when (prop) {
        PROPERTY_NAME -> node.name
        PROPERTY_TYPE -> node.type.className
        PROPERTY_METHOD -> node.method.signature
        else -> null
    }

    private fun getFieldNodeProperty(node: FieldNode, prop: String) = when (prop) {
        PROPERTY_NAME -> node.descriptor.name
        PROPERTY_TYPE -> node.descriptor.type.className
        PROPERTY_CLASS -> node.descriptor.declaringClass.className
        "static" -> node.isStatic
        else -> null
    }

    private fun getParameterNodeProperty(node: ParameterNode, prop: String) = when (prop) {
        "index" -> node.index
        PROPERTY_TYPE -> node.type.className
        PROPERTY_METHOD -> node.method.signature
        else -> null
    }

    private fun getReturnNodeProperty(node: ReturnNode, prop: String) = when (prop) {
        PROPERTY_METHOD -> node.method.signature
        "actual_type" -> node.actualType?.className
        else -> null
    }

    private fun getResourceFileNodeProperty(node: ResourceFileNode, prop: String) = when (prop) {
        "path" -> node.path
        "source" -> node.source
        PROPERTY_FORMAT -> node.format
        PROPERTY_PROFILE -> node.profile
        else -> null
    }

    private fun getResourceValueNodeProperty(node: ResourceValueNode, prop: String) = when (prop) {
        "path" -> node.path
        "key" -> node.key
        PROPERTY_VALUE -> node.value
        PROPERTY_FORMAT -> node.format
        PROPERTY_PROFILE -> node.profile
        else -> null
    }

    private fun getAnnotationNodeProperty(node: AnnotationNode, prop: String) = when (prop) {
        PROPERTY_NAME -> node.name
        PROPERTY_CLASS -> node.className
        "member" -> node.memberName
        "values" -> node.values.toString()
        else -> node.values[prop]
    }

    /**
     * Returns all properties of a node as a map.
     */
    fun getAllProperties(node: Node): Map<String, Any?> = when (node) {
        is CallSiteNode -> mapOf(
            PROPERTY_ID to node.id.value,
            "callee_class" to node.callee.declaringClass.className,
            "callee_name" to node.callee.name,
            "callee_signature" to node.callee.signature,
            "caller_class" to node.caller.declaringClass.className,
            "caller_name" to node.caller.name,
            "caller_signature" to node.caller.signature,
            "line" to node.lineNumber
        )
        is IntConstant -> mapOf(PROPERTY_ID to node.id.value, PROPERTY_VALUE to node.value)
        is StringConstant -> mapOf(PROPERTY_ID to node.id.value, PROPERTY_VALUE to node.value)
        is LongConstant -> mapOf(PROPERTY_ID to node.id.value, PROPERTY_VALUE to node.value)
        is FloatConstant -> mapOf(PROPERTY_ID to node.id.value, PROPERTY_VALUE to node.value)
        is DoubleConstant -> mapOf(PROPERTY_ID to node.id.value, PROPERTY_VALUE to node.value)
        is BooleanConstant -> mapOf(PROPERTY_ID to node.id.value, PROPERTY_VALUE to node.value)
        is NullConstant -> mapOf(PROPERTY_ID to node.id.value, PROPERTY_VALUE to null)
        is EnumConstant -> mapOf(
            PROPERTY_ID to node.id.value,
            PROPERTY_VALUE to node.value,
            PROPERTY_NAME to node.enumName,
            "enum_type" to node.enumType.className
        )
        is LocalVariable -> mapOf(
            PROPERTY_ID to node.id.value,
            PROPERTY_NAME to node.name,
            PROPERTY_TYPE to node.type.className,
            PROPERTY_METHOD to node.method.signature
        )
        is FieldNode -> mapOf(
            PROPERTY_ID to node.id.value,
            PROPERTY_NAME to node.descriptor.name,
            PROPERTY_TYPE to node.descriptor.type.className,
            PROPERTY_CLASS to node.descriptor.declaringClass.className,
            "static" to node.isStatic
        )
        is ParameterNode -> mapOf(
            PROPERTY_ID to node.id.value,
            "index" to node.index,
            PROPERTY_TYPE to node.type.className,
            PROPERTY_METHOD to node.method.signature
        )
        is ReturnNode -> mapOf(
            PROPERTY_ID to node.id.value,
            PROPERTY_METHOD to node.method.signature,
            "actual_type" to node.actualType?.className
        )
        is ResourceFileNode -> mapOf(
            PROPERTY_ID to node.id.value,
            "path" to node.path,
            "source" to node.source,
            PROPERTY_FORMAT to node.format,
            PROPERTY_PROFILE to node.profile
        )
        is ResourceValueNode -> mapOf(
            PROPERTY_ID to node.id.value,
            "path" to node.path,
            "key" to node.key,
            PROPERTY_VALUE to node.value,
            PROPERTY_FORMAT to node.format,
            PROPERTY_PROFILE to node.profile
        )
        is AnnotationNode -> mutableMapOf<String, Any?>(
            PROPERTY_ID to node.id.value,
            PROPERTY_NAME to node.name,
            PROPERTY_CLASS to node.className,
            "member" to node.memberName
        ).also { map -> node.values.forEach { (k, v) -> map[k] = v } }
    }

    /**
     * Maps a Cypher label string to the corresponding Graphite [Node] subclass.
     */
    fun resolveNodeLabel(label: String): Class<out Node> = when (label.lowercase()) {
        "callsitenode", "callsite" -> CallSiteNode::class.java
        "intconstant" -> IntConstant::class.java
        "stringconstant" -> StringConstant::class.java
        "longconstant" -> LongConstant::class.java
        "floatconstant" -> FloatConstant::class.java
        "doubleconstant" -> DoubleConstant::class.java
        "booleanconstant" -> BooleanConstant::class.java
        "nullconstant" -> NullConstant::class.java
        "enumconstant" -> EnumConstant::class.java
        "constant", "constantnode" -> ConstantNode::class.java
        "fieldnode", "field" -> FieldNode::class.java
        "parameternode", "parameter" -> ParameterNode::class.java
        "returnnode", "return" -> ReturnNode::class.java
        "resourcefilenode", "resourcefile" -> ResourceFileNode::class.java
        "resourcevaluenode", "resourcevalue", "resource" -> ResourceValueNode::class.java
        "localvariable", "local" -> LocalVariable::class.java
        "annotationnode", "annotation" -> AnnotationNode::class.java
        "node" -> Node::class.java
        else -> Node::class.java
    }

    /**
     * Maps a Cypher relationship type string to the corresponding Graphite [Edge] subclass.
     */
    fun resolveEdgeType(type: String): Class<out Edge>? = when (type.uppercase()) {
        "DATAFLOW", "DATA_FLOW" -> DataFlowEdge::class.java
        "CALL" -> CallEdge::class.java
        "TYPE" -> TypeEdge::class.java
        "CONTROL_FLOW", "CONTROLFLOW" -> ControlFlowEdge::class.java
        "RESOURCE", "RESOURCE_EDGE", "RESOURCE_OPEN", "RESOURCE_LOAD",
        "RESOURCE_BUNDLE_CANDIDATE", "RESOURCE_LOOKUP", "RESOURCE_KEYS" -> ResourceEdge::class.java
        else -> null
    }
}
