package io.johnsonlee.graphite.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Base interface for all nodes in the analysis graph.
 * Every element in the program that can participate in dataflow is a Node.
 */
sealed interface Node {
    val id: NodeId
}

/**
 * Compact node identifier using Int instead of String for memory efficiency.
 * Saves ~36 bytes per node compared to String-based IDs.
 */
@JvmInline
value class NodeId(val value: Int) {
    companion object {
        private val counter = AtomicInteger(0)

        /**
         * Generate a new unique NodeId.
         */
        fun next(): NodeId = NodeId(counter.incrementAndGet())

        /**
         * Reset the counter (for testing purposes).
         */
        fun reset() {
            counter.set(0)
        }

        /**
         * Create a NodeId from a string (for backward compatibility during migration).
         * Uses the string's hashCode, which may have collisions but is deterministic.
         */
        @Deprecated("Use next() for new code", ReplaceWith("NodeId.next()"))
        fun fromString(value: String): NodeId = NodeId(value.hashCode())
    }

    override fun toString(): String = "node#$value"
}

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
 * Represents a reference from one enum's constructor argument to another enum constant.
 *
 * For example, in:
 * ```java
 * enum TaskConfig {
 *     URGENT(Priority.HIGH);
 *     TaskConfig(Priority priority) { ... }
 * }
 * ```
 * The constructor argument `Priority.HIGH` is stored as:
 * `EnumValueReference(enumClass = "com.example.Priority", enumName = "HIGH")`
 */
data class EnumValueReference(
    val enumClass: String,
    val enumName: String
) {
    override fun toString(): String = "$enumClass.$enumName"
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
 * A resource file within the analyzed archive or directory.
 */
data class ResourceFileNode(
    override val id: NodeId,
    val path: String,
    val source: String,
    val format: String,
    val profile: String? = null
) : Node

/**
 * A value loaded from a resource file such as application.properties or YAML.
 *
 * Modeled as a [ValueNode] so existing DATAFLOW-based analyses can trace
 * configuration values back from code.
 */
data class ResourceValueNode(
    override val id: NodeId,
    val path: String,
    val key: String,
    override val value: Any?,
    val format: String,
    val profile: String? = null
) : ValueNode, ConstantNode

/**
 * A call site - where a method is invoked.
 *
 * [ordinal] tells call sites of the same [caller] and [callee] apart, which no other property
 * does: it counts, in statement order, the invokes of that callee in that method's bytecode,
 * from `0`, every invoke, including the boxing and unboxing calls the graph shows as dataflow
 * rather than as call sites, so `(caller_signature, callee_signature, ordinal)` names one call
 * site and keeps naming it while the method's other statements change. A call the frontend derived rather than read from the
 * bytecode (a function value's dispatch resolved to its body, a lambda body reached through
 * `invokedynamic`, the methods a function object implements) counts apart, from `-1` downwards,
 * so the ordinals of the calls the bytecode spells are the ones a pass over the method body
 * reproduces. `null` in a graph persisted before the property existed.
 *
 * [origin] is, for a call site the frontend derived from another (a call on a function value
 * resolved to the body of the lambda it holds), the call site it was resolved from: the
 * bytecode's own call, the one a fold rule can replace. `null` for every other call site.
 */
data class CallSiteNode(
    override val id: NodeId,
    val caller: MethodDescriptor,
    val callee: MethodDescriptor,
    val lineNumber: Int?,
    val receiver: NodeId?,  // Receiver object for instance method calls (null for static calls)
    val arguments: List<NodeId>, // References to argument value nodes
    val ordinal: Int? = null,
    val origin: NodeId? = null
) : Node

/**
 * An annotation on a class, field, or method.
 *
 * Standalone node that makes annotations queryable via Cypher.
 */
data class AnnotationNode(
    override val id: NodeId,
    val name: String,              // annotation FQN, e.g. "org.springframework.web.bind.annotation.GetMapping"
    val className: String,         // declaring class FQN
    val memberName: String,        // method/field name, or "<class>" for class-level
    val values: Map<String, Any?>  // annotation attributes
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

    /**
     * The JVM method descriptor, `(Ljava/lang/String;)Z`: the parameter and return types the
     * bytecode names the method by. [signature] leaves the return type out, so two methods of
     * one class that differ only in it (a bridge beside its covariant override) share a
     * signature but never a descriptor.
     */
    val descriptor: String get() = jvmMethodDescriptor(parameterTypes.map { it.className }, returnType.className)
}

/** The JVM descriptor of a method with these parameter and return type names, as the graph spells them. */
fun jvmMethodDescriptor(parameterTypes: List<String>, returnType: String): String =
    parameterTypes.joinToString("", "(", ")", transform = ::jvmTypeDescriptor) + jvmTypeDescriptor(returnType)

/** `int` is `I`, `java.lang.String[]` is `[Ljava/lang/String;`: one JVM field descriptor per type name. */
fun jvmTypeDescriptor(typeName: String): String {
    var base = typeName
    val dimensions = StringBuilder()
    while (base.endsWith("[]")) {
        base = base.removeSuffix("[]")
        dimensions.append('[')
    }
    val element = when (base) {
        "boolean" -> "Z"
        "byte" -> "B"
        "char" -> "C"
        "short" -> "S"
        "int" -> "I"
        "long" -> "J"
        "float" -> "F"
        "double" -> "D"
        "void" -> "V"
        else -> "L${base.replace('.', '/')};"
    }
    return dimensions.append(element).toString()
}

data class FieldDescriptor(
    val declaringClass: TypeDescriptor,
    val name: String,
    val type: TypeDescriptor
)
