package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of the Graph interface.
 * Uses fastutil primitive collections for memory-efficient storage.
 *
 * Memory savings vs standard HashMap:
 * - Int2ObjectOpenHashMap: ~40% less memory than HashMap<NodeId, V>
 * - ObjectArrayList: ~20% less memory than ArrayList with better cache locality
 */
class DefaultGraph private constructor(
    private val nodesById: Int2ObjectOpenHashMap<Node>,
    private val outgoingEdges: Int2ObjectOpenHashMap<List<Edge>>,
    private val incomingEdges: Int2ObjectOpenHashMap<List<Edge>>,
    private val methodIndex: Map<String, MethodDescriptor>,
    private val typeHierarchy: TypeHierarchy,
    private val enumValues: Map<String, List<Any?>>,
    private val endpointList: List<EndpointInfo>,
    private val jacksonFieldInfoMap: Map<String, JacksonFieldInfo>,
    private val jacksonGetterInfoMap: Map<String, JacksonFieldInfo>,
    private val rawBranchScopes: Array<RawBranchScope>
) : Graph {

    /**
     * Compact storage for branch scope data, used until [branchScopes] or
     * [branchScopesFor] is first called.
     *
     * Uses [IntArray] (4 bytes/element) instead of `Set<NodeId>`
     * (`HashSet<boxed Integer>`, ~36 bytes/element) so the idle cost is
     * roughly **9× smaller** per branch entry.  The full [BranchScope]
     * objects and lookup index are materialised lazily on first access.
     */
    internal class RawBranchScope(
        val conditionNodeId: Int,
        val method: MethodDescriptor,
        val comparison: BranchComparison,
        val trueBranchNodeIds: IntArray,
        val falseBranchNodeIds: IntArray
    )

    /** Materialised on first access from [rawBranchScopes]. */
    private val branchScopeIndex: Map<Int, List<BranchScope>> by lazy {
        rawBranchScopes.map { raw ->
            BranchScope(
                conditionNodeId = NodeId(raw.conditionNodeId),
                method = raw.method,
                comparison = raw.comparison,
                trueBranchNodeIds = IntOpenHashSet(raw.trueBranchNodeIds),
                falseBranchNodeIds = IntOpenHashSet(raw.falseBranchNodeIds)
            )
        }.groupBy { it.conditionNodeId.value }
    }

    override fun node(id: NodeId): Node? = nodesById.get(id.value)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
        nodesById.values.asSequence().filter { type.isInstance(it) } as Sequence<T>

    override fun outgoing(id: NodeId): Sequence<Edge> =
        outgoingEdges.get(id.value)?.asSequence() ?: emptySequence()

    override fun incoming(id: NodeId): Sequence<Edge> =
        incomingEdges.get(id.value)?.asSequence() ?: emptySequence()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> =
        outgoing(id).filter { type.isInstance(it) } as Sequence<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T : Edge> incoming(id: NodeId, type: Class<T>): Sequence<T> =
        incoming(id).filter { type.isInstance(it) } as Sequence<T>

    override fun callSites(methodPattern: MethodPattern): Sequence<CallSiteNode> =
        nodes(CallSiteNode::class.java).filter { callSite ->
            methodPattern.matches(callSite.callee)
        }

    override fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        typeHierarchy.supertypes(type)

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        typeHierarchy.subtypes(type)

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
        methodIndex.values.asSequence().filter { pattern.matches(it) }

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        enumValues["$enumClass#$enumName"]

    override fun endpoints(pattern: String?, httpMethod: HttpMethod?): Sequence<EndpointInfo> {
        return endpointList.asSequence().filter { endpoint ->
            val pathMatches = pattern == null || endpoint.matchesPattern(pattern)
            val methodMatches = httpMethod == null || endpoint.httpMethod == httpMethod || endpoint.httpMethod == HttpMethod.ANY
            pathMatches && methodMatches
        }
    }

    override fun branchScopes(): Sequence<BranchScope> =
        branchScopeIndex.values.asSequence().flatMap { it.asSequence() }

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        branchScopeIndex[conditionNodeId.value]?.asSequence() ?: emptySequence()

    override fun jacksonFieldInfo(className: String, fieldName: String): JacksonFieldInfo? =
        jacksonFieldInfoMap["$className#$fieldName"]

    override fun jacksonGetterInfo(className: String, methodName: String): JacksonFieldInfo? =
        jacksonGetterInfoMap["$className#$methodName"]

    /**
     * Builder for constructing DefaultGraph instances.
     * Uses concurrent collections during building, then compacts to fastutil collections.
     */
    class Builder : GraphBuilder {
        private val nodes = Int2ObjectOpenHashMap<Node>()
        private val outgoing = Int2ObjectOpenHashMap<MutableList<Edge>>()
        private val incoming = Int2ObjectOpenHashMap<MutableList<Edge>>()
        private val methods = ConcurrentHashMap<String, MethodDescriptor>()
        private val typeHierarchyBuilder = TypeHierarchy.Builder()
        private val enumValues = ConcurrentHashMap<String, List<Any?>>()
        private val endpoints = ObjectArrayList<EndpointInfo>()
        private val jacksonFieldInfo = ConcurrentHashMap<String, JacksonFieldInfo>()
        private val jacksonGetterInfo = ConcurrentHashMap<String, JacksonFieldInfo>()
        private val branchScopes = ObjectArrayList<RawBranchScope>()

        override fun addNode(node: Node): GraphBuilder {
            nodes.put(node.id.value, node)
            return this
        }

        override fun addEdge(edge: Edge): GraphBuilder {
            outgoing.computeIfAbsent(edge.from.value) { ObjectArrayList() }.add(edge)
            incoming.computeIfAbsent(edge.to.value) { ObjectArrayList() }.add(edge)
            return this
        }

        fun addMethod(method: MethodDescriptor): Builder {
            methods[method.signature] = method
            return this
        }

        fun addTypeRelation(subtype: TypeDescriptor, supertype: TypeDescriptor, relation: TypeRelation): Builder {
            typeHierarchyBuilder.addRelation(subtype, supertype, relation)
            return this
        }

        fun addEnumValues(enumClass: String, enumName: String, values: List<Any?>): Builder {
            enumValues["$enumClass#$enumName"] = values
            return this
        }

        fun addEndpoint(endpoint: EndpointInfo): Builder {
            endpoints.add(endpoint)
            return this
        }

        fun addJacksonFieldInfo(className: String, fieldName: String, info: JacksonFieldInfo): Builder {
            jacksonFieldInfo["$className#$fieldName"] = info
            return this
        }

        fun addJacksonGetterInfo(className: String, methodName: String, info: JacksonFieldInfo): Builder {
            jacksonGetterInfo["$className#$methodName"] = info
            return this
        }

        fun addBranchScope(
            conditionNodeId: NodeId,
            method: MethodDescriptor,
            comparison: BranchComparison,
            trueBranchNodeIds: IntArray,
            falseBranchNodeIds: IntArray
        ): Builder {
            branchScopes.add(RawBranchScope(
                conditionNodeId = conditionNodeId.value,
                method = method,
                comparison = comparison,
                trueBranchNodeIds = trueBranchNodeIds,
                falseBranchNodeIds = falseBranchNodeIds
            ))
            return this
        }

        override fun build(): Graph {
            // Compact edge lists to immutable form
            val compactOutgoing = Int2ObjectOpenHashMap<List<Edge>>(outgoing.size)
            outgoing.int2ObjectEntrySet().forEach { entry ->
                compactOutgoing.put(entry.intKey, entry.value.toList())
            }

            val compactIncoming = Int2ObjectOpenHashMap<List<Edge>>(incoming.size)
            incoming.int2ObjectEntrySet().forEach { entry ->
                compactIncoming.put(entry.intKey, entry.value.toList())
            }

            // Trim to size for memory efficiency
            nodes.trim()
            compactOutgoing.trim()
            compactIncoming.trim()

            return DefaultGraph(
                nodesById = nodes,
                outgoingEdges = compactOutgoing,
                incomingEdges = compactIncoming,
                methodIndex = methods.toMap(),
                typeHierarchy = typeHierarchyBuilder.build(),
                enumValues = enumValues.toMap(),
                endpointList = endpoints.toList(),
                jacksonFieldInfoMap = jacksonFieldInfo.toMap(),
                jacksonGetterInfoMap = jacksonGetterInfo.toMap(),
                rawBranchScopes = branchScopes.toTypedArray()
            )
        }
    }
}

/**
 * Type hierarchy for efficient subtype/supertype queries
 */
class TypeHierarchy private constructor(
    private val supertypeMap: Map<String, Set<TypeDescriptor>>,
    private val subtypeMap: Map<String, Set<TypeDescriptor>>
) {
    fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        supertypeMap[type.className]?.asSequence() ?: emptySequence()

    fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        subtypeMap[type.className]?.asSequence() ?: emptySequence()

    class Builder {
        private val supertypes = mutableMapOf<String, MutableSet<TypeDescriptor>>()
        private val subtypes = mutableMapOf<String, MutableSet<TypeDescriptor>>()

        fun addRelation(subtype: TypeDescriptor, supertype: TypeDescriptor, relation: TypeRelation): Builder {
            supertypes.getOrPut(subtype.className) { mutableSetOf() }.add(supertype)
            subtypes.getOrPut(supertype.className) { mutableSetOf() }.add(subtype)
            return this
        }

        fun build(): TypeHierarchy = TypeHierarchy(
            supertypeMap = supertypes.mapValues { it.value.toSet() },
            subtypeMap = subtypes.mapValues { it.value.toSet() }
        )
    }
}
