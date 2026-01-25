package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of the Graph interface.
 * Uses in-memory hash maps for storage and retrieval.
 */
class DefaultGraph private constructor(
    private val nodesById: Map<NodeId, Node>,
    private val outgoingEdges: Map<NodeId, List<Edge>>,
    private val incomingEdges: Map<NodeId, List<Edge>>,
    private val methodIndex: Map<String, MethodDescriptor>,
    private val typeHierarchy: TypeHierarchy,
    private val enumValues: Map<String, List<Any?>>,  // key: "enumClass#enumName", value: list of constructor args
    private val endpointList: List<EndpointInfo>
) : Graph {

    override fun node(id: NodeId): Node? = nodesById[id]

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
        nodesById.values.asSequence().filter { type.isInstance(it) } as Sequence<T>

    override fun outgoing(id: NodeId): Sequence<Edge> =
        outgoingEdges[id]?.asSequence() ?: emptySequence()

    override fun incoming(id: NodeId): Sequence<Edge> =
        incomingEdges[id]?.asSequence() ?: emptySequence()

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

    /**
     * Builder for constructing DefaultGraph instances
     */
    class Builder : GraphBuilder {
        private val nodes = ConcurrentHashMap<NodeId, Node>()
        private val outgoing = ConcurrentHashMap<NodeId, MutableList<Edge>>()
        private val incoming = ConcurrentHashMap<NodeId, MutableList<Edge>>()
        private val methods = ConcurrentHashMap<String, MethodDescriptor>()
        private val typeHierarchyBuilder = TypeHierarchy.Builder()
        private val enumValues = ConcurrentHashMap<String, List<Any?>>()
        private val endpoints = mutableListOf<EndpointInfo>()

        override fun addNode(node: Node): GraphBuilder {
            nodes[node.id] = node
            return this
        }

        override fun addEdge(edge: Edge): GraphBuilder {
            outgoing.getOrPut(edge.from) { mutableListOf() }.add(edge)
            incoming.getOrPut(edge.to) { mutableListOf() }.add(edge)

            // Track type hierarchy from TypeEdges
            if (edge is TypeEdge) {
                val fromNode = nodes[edge.from]
                val toNode = nodes[edge.to]
                // Type hierarchy is built separately
            }

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

        /**
         * Add an enum constant's underlying values.
         * @param enumClass fully qualified enum class name
         * @param enumName the name of the enum constant
         * @param values list of constructor arguments (excluding name and ordinal)
         */
        fun addEnumValues(enumClass: String, enumName: String, values: List<Any?>): Builder {
            enumValues["$enumClass#$enumName"] = values
            return this
        }

        /**
         * Add an HTTP endpoint.
         */
        fun addEndpoint(endpoint: EndpointInfo): Builder {
            endpoints.add(endpoint)
            return this
        }

        override fun build(): Graph = DefaultGraph(
            nodesById = nodes.toMap(),
            outgoingEdges = outgoing.mapValues { it.value.toList() },
            incomingEdges = incoming.mapValues { it.value.toList() },
            methodIndex = methods.toMap(),
            typeHierarchy = typeHierarchyBuilder.build(),
            enumValues = enumValues.toMap(),
            endpointList = endpoints.toList()
        )
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
