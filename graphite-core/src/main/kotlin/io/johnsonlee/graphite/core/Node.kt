package io.johnsonlee.graphite.core

/**
 * Base interface for all nodes in the analysis graph.
 * Every element in the program that can participate in dataflow is a Node.
 */
sealed interface Node {
    val id: NodeId
}

@JvmInline
value class NodeId(val value: String)

/**
 * A value node represents something that holds or produces a value:
 * - Local variables
 * - Fields
 * - Method parameters
 * - Return values
 * - Constants
 */
sealed interface ValueNode : Node

/**
 * Constant values that can be statically determined
 */
sealed interface ConstantNode : ValueNode {
    val value: Any?
}

data class IntConstant(
    override val id: NodeId,
    override val value: Int
) : ConstantNode

data class StringConstant(
    override val id: NodeId,
    override val value: String
) : ConstantNode

data class EnumConstant(
    override val id: NodeId,
    val enumType: TypeDescriptor,
    val enumName: String,
    val constructorArgs: List<Any?> = emptyList()  // User-defined constructor arguments (excluding name and ordinal)
) : ConstantNode {
    /**
     * The primary value (first constructor argument), for convenience.
     * For enums like `CHECKOUT(1001)`, this returns 1001.
     */
    override val value: Any? get() = constructorArgs.firstOrNull()
}

data class LongConstant(
    override val id: NodeId,
    override val value: Long
) : ConstantNode

data class FloatConstant(
    override val id: NodeId,
    override val value: Float
) : ConstantNode

data class DoubleConstant(
    override val id: NodeId,
    override val value: Double
) : ConstantNode

data class BooleanConstant(
    override val id: NodeId,
    override val value: Boolean
) : ConstantNode

data class NullConstant(
    override val id: NodeId
) : ConstantNode {
    override val value: Any? = null
}

/**
 * A local variable in a method
 */
data class LocalVariable(
    override val id: NodeId,
    val name: String,
    val type: TypeDescriptor,
    val method: MethodDescriptor
) : ValueNode

/**
 * A field (instance or static)
 */
data class FieldNode(
    override val id: NodeId,
    val descriptor: FieldDescriptor,
    val isStatic: Boolean
) : ValueNode

/**
 * A method parameter
 */
data class ParameterNode(
    override val id: NodeId,
    val index: Int,
    val type: TypeDescriptor,
    val method: MethodDescriptor
) : ValueNode

/**
 * A method return value
 */
data class ReturnNode(
    override val id: NodeId,
    val method: MethodDescriptor,
    val actualType: TypeDescriptor? = null // Resolved actual type (for generics)
) : ValueNode

/**
 * A call site - where a method is invoked
 */
data class CallSiteNode(
    override val id: NodeId,
    val caller: MethodDescriptor,
    val callee: MethodDescriptor,
    val lineNumber: Int?,
    val arguments: List<NodeId> // References to argument value nodes
) : Node

/**
 * Descriptors for program elements
 */
data class TypeDescriptor(
    val className: String,
    val typeArguments: List<TypeDescriptor> = emptyList()
) {
    val simpleName: String get() = className.substringAfterLast('.')

    fun isSubtypeOf(other: TypeDescriptor): Boolean {
        // Will be implemented using type hierarchy graph
        return className == other.className
    }
}

data class MethodDescriptor(
    val declaringClass: TypeDescriptor,
    val name: String,
    val parameterTypes: List<TypeDescriptor>,
    val returnType: TypeDescriptor
) {
    val signature: String get() = "${declaringClass.className}.$name(${parameterTypes.joinToString(",") { it.className }})"
}

data class FieldDescriptor(
    val declaringClass: TypeDescriptor,
    val name: String,
    val type: TypeDescriptor
)
