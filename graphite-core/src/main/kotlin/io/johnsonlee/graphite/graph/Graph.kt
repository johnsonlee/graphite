package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*

/**
 * The unified program graph that combines all analysis graphs.
 * This is the central abstraction of Graphite.
 *
 * Key design decisions:
 * 1. Immutable - once built, the graph doesn't change
 * 2. Queryable - supports efficient traversal and pattern matching
 * 3. Composable - can be built incrementally from different sources
 */
interface Graph {
    /**
     * Get a node by its ID
     */
    fun node(id: NodeId): Node?

    /**
     * Get all nodes of a specific type
     */
    fun <T : Node> nodes(type: Class<T>): Sequence<T>

    /**
     * Get all outgoing edges from a node
     */
    fun outgoing(id: NodeId): Sequence<Edge>

    /**
     * Get all incoming edges to a node
     */
    fun incoming(id: NodeId): Sequence<Edge>

    /**
     * Get outgoing edges of a specific type
     */
    fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T>

    /**
     * Get incoming edges of a specific type
     */
    fun <T : Edge> incoming(id: NodeId, type: Class<T>): Sequence<T>

    /**
     * Find all call sites that invoke a method matching the pattern
     */
    fun callSites(methodPattern: MethodPattern): Sequence<CallSiteNode>

    /**
     * Get the type hierarchy for a type
     */
    fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor>
    fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor>

    /**
     * Find methods matching a pattern
     */
    fun methods(pattern: MethodPattern): Sequence<MethodDescriptor>

    /**
     * Get the underlying values for an enum constant.
     * Enum constructors can have multiple user-defined arguments.
     *
     * @param enumClass fully qualified enum class name
     * @param enumName the name of the enum constant
     * @return list of constructor arguments (excluding name and ordinal), or null if not available
     */
    fun enumValues(enumClass: String, enumName: String): List<Any?>?

    /**
     * Get all HTTP endpoints extracted from annotations.
     *
     * @param pattern optional endpoint path pattern (supports * and ** wildcards)
     * @param httpMethod optional HTTP method filter
     * @return sequence of matching endpoints
     */
    fun endpoints(pattern: String? = null, httpMethod: HttpMethod? = null): Sequence<EndpointInfo>
}

/**
 * Pattern for matching methods.
 * Supports wildcards and annotations.
 */
data class MethodPattern(
    val declaringClass: String? = null,        // e.g., "com.example.*" or regex ".*Client" when useRegex=true
    val name: String? = null,                  // e.g., "getOption" or regex "getOption.*" when useRegex=true
    val parameterTypes: List<String>? = null,  // e.g., ["java.lang.Integer"] or null for any
    val returnType: String? = null,
    val annotations: List<String> = emptyList(), // e.g., ["org.springframework.web.bind.annotation.GetMapping"]
    val useRegex: Boolean = false              // when true, declaringClass and name are treated as regex patterns
) {
    fun matches(method: MethodDescriptor): Boolean {
        if (declaringClass != null && !matchesPattern(method.declaringClass.className, declaringClass)) {
            return false
        }
        if (name != null && !matchesPattern(method.name, name)) {
            return false
        }
        if (parameterTypes != null) {
            if (method.parameterTypes.size != parameterTypes.size) return false
            if (!method.parameterTypes.zip(parameterTypes).all { (actual, pattern) ->
                    matchesPattern(actual.className, pattern)
                }) return false
        }
        if (returnType != null && !matchesPattern(method.returnType.className, returnType)) {
            return false
        }
        return true
    }

    private fun matchesPattern(actual: String, pattern: String): Boolean {
        return if (useRegex) {
            pattern.toRegex().matches(actual)
        } else if (pattern.endsWith("*")) {
            actual.startsWith(pattern.dropLast(1))
        } else {
            actual == pattern
        }
    }
}

/**
 * Builder for constructing graphs
 */
interface GraphBuilder {
    fun addNode(node: Node): GraphBuilder
    fun addEdge(edge: Edge): GraphBuilder
    fun build(): Graph
}

/**
 * Extension functions for convenient traversal
 */
inline fun <reified T : Node> Graph.nodes(): Sequence<T> = nodes(T::class.java)
inline fun <reified T : Edge> Graph.outgoing(id: NodeId): Sequence<T> = outgoing(id, T::class.java)
inline fun <reified T : Edge> Graph.incoming(id: NodeId): Sequence<T> = incoming(id, T::class.java)
