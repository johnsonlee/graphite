package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*

/**
 * Resolves Cypher property names to actual values on Graphite nodes.
 */
object NodePropertyAccessor {

    fun getProperty(node: Node, property: String): Any? {
        // Check global properties first (except "type" which is ambiguous)
        if (property == "id") return node.id.value

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
            is AnnotationNode -> getAnnotationNodeProperty(node, property)
        }
        if (nodeSpecific != null) return nodeSpecific

        // Fall back to global properties
        return when (property) {
            "type" -> nodeTypeName(node)
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
        "value" -> node.value
        else -> null
    }

    private fun getStringConstantProperty(node: StringConstant, prop: String) = when (prop) {
        "value" -> node.value
        else -> null
    }

    private fun getLongConstantProperty(node: LongConstant, prop: String) = when (prop) {
        "value" -> node.value
        else -> null
    }

    private fun getFloatConstantProperty(node: FloatConstant, prop: String) = when (prop) {
        "value" -> node.value
        else -> null
    }

    private fun getDoubleConstantProperty(node: DoubleConstant, prop: String) = when (prop) {
        "value" -> node.value
        else -> null
    }

    private fun getBooleanConstantProperty(node: BooleanConstant, prop: String) = when (prop) {
        "value" -> node.value
        else -> null
    }

    private fun getEnumConstantProperty(node: EnumConstant, prop: String) = when (prop) {
        "value" -> node.value
        "name" -> node.enumName
        "enum_type" -> node.enumType.className
        else -> null
    }

    private fun getLocalVariableProperty(node: LocalVariable, prop: String) = when (prop) {
        "name" -> node.name
        "type" -> node.type.className
        "method" -> node.method.signature
        else -> null
    }

    private fun getFieldNodeProperty(node: FieldNode, prop: String) = when (prop) {
        "name" -> node.descriptor.name
        "type" -> node.descriptor.type.className
        "class" -> node.descriptor.declaringClass.className
        "static" -> node.isStatic
        else -> null
    }

    private fun getParameterNodeProperty(node: ParameterNode, prop: String) = when (prop) {
        "index" -> node.index
        "type" -> node.type.className
        "method" -> node.method.signature
        else -> null
    }

    private fun getReturnNodeProperty(node: ReturnNode, prop: String) = when (prop) {
        "method" -> node.method.signature
        "actual_type" -> node.actualType?.className
        else -> null
    }

    private fun getAnnotationNodeProperty(node: AnnotationNode, prop: String) = when (prop) {
        "name" -> node.name
        "class" -> node.className
        "member" -> node.memberName
        "values" -> node.values.toString()
        else -> node.values[prop]
    }

    /**
     * Returns all properties of a node as a map.
     */
    fun getAllProperties(node: Node): Map<String, Any?> = when (node) {
        is CallSiteNode -> mapOf(
            "id" to node.id.value,
            "callee_class" to node.callee.declaringClass.className,
            "callee_name" to node.callee.name,
            "callee_signature" to node.callee.signature,
            "caller_class" to node.caller.declaringClass.className,
            "caller_name" to node.caller.name,
            "caller_signature" to node.caller.signature,
            "line" to node.lineNumber
        )
        is IntConstant -> mapOf("id" to node.id.value, "value" to node.value)
        is StringConstant -> mapOf("id" to node.id.value, "value" to node.value)
        is LongConstant -> mapOf("id" to node.id.value, "value" to node.value)
        is FloatConstant -> mapOf("id" to node.id.value, "value" to node.value)
        is DoubleConstant -> mapOf("id" to node.id.value, "value" to node.value)
        is BooleanConstant -> mapOf("id" to node.id.value, "value" to node.value)
        is NullConstant -> mapOf("id" to node.id.value, "value" to null)
        is EnumConstant -> mapOf(
            "id" to node.id.value,
            "value" to node.value,
            "name" to node.enumName,
            "enum_type" to node.enumType.className
        )
        is LocalVariable -> mapOf(
            "id" to node.id.value,
            "name" to node.name,
            "type" to node.type.className,
            "method" to node.method.signature
        )
        is FieldNode -> mapOf(
            "id" to node.id.value,
            "name" to node.descriptor.name,
            "type" to node.descriptor.type.className,
            "class" to node.descriptor.declaringClass.className,
            "static" to node.isStatic
        )
        is ParameterNode -> mapOf(
            "id" to node.id.value,
            "index" to node.index,
            "type" to node.type.className,
            "method" to node.method.signature
        )
        is ReturnNode -> mapOf(
            "id" to node.id.value,
            "method" to node.method.signature,
            "actual_type" to node.actualType?.className
        )
        is AnnotationNode -> mutableMapOf<String, Any?>(
            "id" to node.id.value,
            "name" to node.name,
            "class" to node.className,
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
        else -> null
    }
}
