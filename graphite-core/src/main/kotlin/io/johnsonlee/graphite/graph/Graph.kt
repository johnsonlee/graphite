package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.input.ResourceAccessor

data class ClassDependency(
    val callerClass: String,
    val calleeClass: String
)

data class ClassOverview(
    val classCounts: Map<String, Int>,
    val classEdges: Map<ClassDependency, Int>,
    val callSiteCount: Int
)

enum class StringMatchMode {
    STARTS_WITH,
    ENDS_WITH,
    CONTAINS
}

/** Exact value transformation applied before a storage-backed string match. */
enum class StringValueTransform {
    LOWERCASE
}

/** One exact predicate in a storage-backed disjunction lookup. */
data class StringPropertyPredicate(
    val property: String,
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

/**
 * Optional storage capability for graphs that can avoid materializing a full node scan.
 *
 * When the same graph also implements [StringPropertyLookupOrder], every non-null sequence
 * returned by this capability must be monotonic in [StringPropertyLookupOrder.stringPropertyNodeOrder].
 */
interface StringPropertyLookup {
    fun <T : Node> nodesByStringProperty(
        type: Class<T>,
        property: String,
        mode: StringMatchMode,
        expected: String,
        limit: Int
    ): Sequence<T>?
}

/** Receives one callback for each storage item inspected by a graph lookup. */
fun interface GraphWorkConsumer {
    fun consume()
}

/** Optional string lookup capability that exposes its internal work to callers. */
interface WorkAwareStringPropertyLookup : StringPropertyLookup {
    fun <T : Node> nodesByStringProperty(
        type: Class<T>,
        property: String,
        mode: StringMatchMode,
        expected: String,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): Sequence<T>?
}

/**
 * Optional storage capability for matching a precisely transformed string value.
 *
 * When the same graph also implements [StringPropertyLookupOrder], every non-null sequence
 * returned by this capability must be monotonic in [StringPropertyLookupOrder.stringPropertyNodeOrder].
 */
interface TransformedStringPropertyLookup {
    fun <T : Node> nodesByTransformedStringProperty(
        type: Class<T>,
        property: String,
        transform: StringValueTransform,
        mode: StringMatchMode,
        expected: String,
        limit: Int
    ): Sequence<T>?
}

/** Transformed string lookup capability that exposes every inspected work item. */
interface WorkAwareTransformedStringPropertyLookup : TransformedStringPropertyLookup {
    fun <T : Node> nodesByTransformedStringProperty(
        type: Class<T>,
        property: String,
        transform: StringValueTransform,
        mode: StringMatchMode,
        expected: String,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): Sequence<T>?
}

/**
 * Optional capability for matching several string properties in one canonical node scan.
 * Returned nodes must match at least one predicate and must not contain duplicates.
 */
interface StringPropertyDisjunctionLookup {
    fun <T : Node> nodesByStringPropertyDisjunction(
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int
    ): Sequence<T>?
}

/** Disjunction lookup capability that exposes each inspected node as one work item. */
interface WorkAwareStringPropertyDisjunctionLookup : StringPropertyDisjunctionLookup {
    fun <T : Node> nodesByStringPropertyDisjunction(
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): Sequence<T>?
}

/** Optional planning hint for avoiding worker overhead once a mapped lookup is warm. */
interface StringPropertyDisjunctionLookupStrategy {
    fun prefersSerialStringPropertyDisjunction(type: Class<out Node>): Boolean
}

/**
 * Ordering capability for storage lookups that are emitted in canonical node traversal order.
 * Implementations must return a stable key and must emit all string-property lookup sequences
 * monotonically by that key.
 */
interface StringPropertyLookupOrder {
    fun stringPropertyNodeOrder(node: Node): Long
}

/**
 * The unified program graph that combines all analysis graphs.
 * This is the central abstraction of Graphite.
 *
 * Key design decisions:
 * 1. Immutable - once built, the graph doesn't change
 * 2. Queryable - supports efficient traversal and pattern matching
 * 3. Composable - can be built incrementally from different sources
 */
@Suppress("TooManyFunctions")
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
     * Return a precomputed node count for [type] when the graph can answer
     * without scanning/deserializing nodes. Implementations may return null
     * to indicate callers should fall back to [nodes].
     */
    fun nodeCount(type: Class<out Node>): Long? = null

    /**
     * Return a precomputed edge count when the graph can answer without
     * scanning every node's adjacency list.
     */
    fun edgeCount(): Long? = null

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
     * Return a precomputed method count when the graph can answer without
     * materializing every method descriptor.
     */
    fun methodCount(): Long? = null

    /**
     * Return up to [limit] methods matching [pattern] when the graph can do so
     * without materializing the full method index.
     */
    fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor>? = null

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
     * Get annotations for a class member (field or method).
     *
     * @param className fully qualified class name
     * @param memberName field name or method name
     * @return map of annotation FQN to annotation values, or empty map if none
     */
    fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>>

    /**
     * Return all member annotations keyed by "$className#$memberName" when
     * the graph already stores that index. Implementations may return null to
     * let callers discover annotated members by scanning nodes.
     */
    fun memberAnnotationIndex(): Map<String, Map<String, Map<String, Any?>>>? = null

    /**
     * Access resource files from the analyzed archive (JAR, WAR, directory).
     */
    val resources: ResourceAccessor

    /**
     * Get all branch scopes in the graph.
     * Each BranchScope records which nodes belong to each branch of a condition.
     */
    fun branchScopes(): Sequence<BranchScope>

    /**
     * Get branch scopes where the given node is the condition operand.
     */
    fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope>

    /**
     * Get all type names that have type hierarchy information (supertypes or subtypes).
     */
    fun typeHierarchyTypes(): Set<String> = emptySet()

    /**
     * Get the origin artifact or source container for the given class, if known.
     *
     * Examples:
     * - `my-app.jar`
     * - a Spring Boot nested library origin
     * - a Spring Boot application classes origin
     */
    fun classOrigin(className: String): String? = null

    /**
     * Get all known class origins keyed by fully qualified class name.
     */
    fun classOrigins(): Map<String, String> = emptyMap()

    /**
     * Get artifact-level dependency weights keyed by source artifact, then target artifact.
     *
     * Example:
     * - `elasticsearch-8.17.0 -> lucene-core-9.12.0`
     */
    fun artifactDependencies(): Map<String, Map<String, Int>> = emptyMap()

    /**
     * Return a precomputed class-level call overview when the graph can answer
     * without scanning/deserializing call-site nodes.
     */
    fun classOverview(limit: Int): ClassOverview? = null

}

/**
 * Use a storage-aware string lookup when [Graph] also implements
 * [StringPropertyLookup]. Existing graph implementations remain binary
 * compatible and return null through this extension.
 */
fun <T : Node> Graph.nodesByStringProperty(
    type: Class<T>,
    property: String,
    mode: StringMatchMode,
    expected: String,
    limit: Int = Int.MAX_VALUE
): Sequence<T>? = (this as? StringPropertyLookup)
    ?.nodesByStringProperty(type, property, mode, expected, limit)

/** Use a storage lookup only when it can report every inspected work item. */
fun <T : Node> Graph.nodesByStringProperty(
    type: Class<T>,
    property: String,
    mode: StringMatchMode,
    expected: String,
    limit: Int,
    workConsumer: GraphWorkConsumer
): Sequence<T>? = (this as? WorkAwareStringPropertyLookup)
    ?.nodesByStringProperty(type, property, mode, expected, limit, workConsumer)

/** Use a storage-aware transformed string lookup when the graph supports it. */
fun <T : Node> Graph.nodesByTransformedStringProperty(
    type: Class<T>,
    property: String,
    transform: StringValueTransform,
    mode: StringMatchMode,
    expected: String,
    limit: Int = Int.MAX_VALUE
): Sequence<T>? = (this as? TransformedStringPropertyLookup)
    ?.nodesByTransformedStringProperty(type, property, transform, mode, expected, limit)

/** Use a transformed lookup only when it can report every inspected work item. */
fun <T : Node> Graph.nodesByTransformedStringProperty(
    type: Class<T>,
    property: String,
    transform: StringValueTransform,
    mode: StringMatchMode,
    expected: String,
    limit: Int,
    workConsumer: GraphWorkConsumer
): Sequence<T>? = (this as? WorkAwareTransformedStringPropertyLookup)
    ?.nodesByTransformedStringProperty(type, property, transform, mode, expected, limit, workConsumer)

/** Use a fused storage lookup when the graph can evaluate the complete disjunction exactly. */
fun <T : Node> Graph.nodesByStringPropertyDisjunction(
    type: Class<T>,
    predicates: List<StringPropertyPredicate>,
    limit: Int = Int.MAX_VALUE
): Sequence<T>? = (this as? StringPropertyDisjunctionLookup)
    ?.nodesByStringPropertyDisjunction(type, predicates, limit)

/** Use a fused disjunction lookup only when it can report every inspected node. */
fun <T : Node> Graph.nodesByStringPropertyDisjunction(
    type: Class<T>,
    predicates: List<StringPropertyPredicate>,
    limit: Int,
    workConsumer: GraphWorkConsumer
): Sequence<T>? = (this as? WorkAwareStringPropertyDisjunctionLookup)
    ?.nodesByStringPropertyDisjunction(type, predicates, limit, workConsumer)

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
