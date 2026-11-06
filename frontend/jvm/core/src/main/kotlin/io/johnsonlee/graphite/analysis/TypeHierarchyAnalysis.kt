package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeHierarchyConfig
import io.johnsonlee.graphite.core.TypeHierarchyResult
import io.johnsonlee.graphite.core.TypeStructure
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes

internal const val JAVA_LANG_OBJECT = "java.lang.Object"
internal const val UNKNOWN_TYPE = "unknown"
internal const val VOID_TYPE = "void"
internal const val BOOLEAN_PRIMITIVE = "boolean"
internal const val JAVA_LANG_BOOLEAN = "java.lang.Boolean"
internal const val SETTER_PREFIX = "set"
internal const val GETTER_PREFIX = "get"
internal const val BOOLEAN_GETTER_PREFIX = "is"
internal const val LOCAL_TYPE_TRACE_MAX_DEPTH = 5
internal const val TYPE_GETTER_PREFIX_LENGTH = 3
internal const val TYPE_BOOLEAN_GETTER_PREFIX_LENGTH = 2

internal fun fieldKey(declaringClass: String, fieldName: String): String = "$declaringClass#$fieldName"

/**
 * Analyzes type hierarchies to build complete type structure information.
 *
 * This analysis discovers:
 * 1. Actual types returned by methods (not just declared return types)
 * 2. Actual types assigned to Object fields
 * 3. Actual types used for generic type parameters
 * 4. Nested type structures recursively
 *
 * Example output for a method returning ApiResponse<PageData<User>>:
 * ```
 * ApiResponse<PageData<User>>
 * ├── data: PageData<User>
 * │   ├── items: List<User>
 * │   └── extra: Object → PageMetadata
 * └── metadata: Object → RequestMetadata
 * ```
 */
class TypeHierarchyAnalysis(internal val graph: Graph, internal val config: TypeHierarchyConfig = TypeHierarchyConfig()) {
    // Cache for analyzed types to avoid recomputation
    internal val typeCache = mutableMapOf<String, TypeStructure>()

    // Track methods being analyzed to prevent infinite recursion
    internal val analyzingMethods = mutableSetOf<String>()

    // Global field assignment cache: fieldKey -> set of assigned type class names
    // Key format: "declaringClass#fieldName"
    internal val globalFieldAssignments: Map<String, Set<String>> by lazy { buildGlobalFieldAssignments() }

    /**
     * Analyze the return type hierarchy for methods matching the pattern.
     *
     * @param pattern Method pattern to match
     * @return List of type hierarchy results for each matching method
     */
    fun analyzeReturnTypes(pattern: MethodPattern): List<TypeHierarchyResult> {
        return graph.methods(pattern).map { method -> analyzeMethod(method) }.toList()
    }

    /** Analyze a specific method's return type hierarchy. */
    fun analyzeMethod(method: MethodDescriptor): TypeHierarchyResult {
        val methodSignature = method.signature

        // Prevent infinite recursion for recursive methods
        if (methodSignature in analyzingMethods) {
            return TypeHierarchyResult(method = method, returnStructures = setOf(TypeStructure.from(method.returnType)))
        }

        analyzingMethods.add(methodSignature)

        try {
            // Find all return statements in this method
            val returnNodes = graph.nodes<ReturnNode>().filter { it.method.signature == methodSignature }.toList()

            // For each return, find the actual type being returned
            val returnStructures = mutableSetOf<TypeStructure>()
            val callSiteStructures = mutableMapOf<CallSiteNode, TypeStructure>()

            returnNodes.forEach { returnNode ->
                val structures = analyzeReturnNode(returnNode, method, 0)
                returnStructures.addAll(structures)
            }

            return TypeHierarchyResult(method = method, returnStructures = returnStructures, callSiteStructures = callSiteStructures)
        } finally {
            analyzingMethods.remove(methodSignature)
        }
    }

    /** Analyze a return node to find the actual type structure. */
    private fun analyzeReturnNode(returnNode: ReturnNode, method: MethodDescriptor, depth: Int): Set<TypeStructure> {
        if (depth > config.maxDepth) {
            return emptySet()
        }

        val structures = mutableSetOf<TypeStructure>()
        val visited = mutableSetOf<NodeId>()

        // Trace backward from return to find actual types
        traceTypeFromNode(returnNode.id, method, structures, visited, depth)

        return structures
    }
}
