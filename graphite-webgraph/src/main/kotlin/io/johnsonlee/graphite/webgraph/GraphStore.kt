package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.webgraph.ArrayListMutableGraph
import it.unimi.dsi.webgraph.BVGraph
import it.unimi.dsi.webgraph.Transform
import java.nio.file.Files
import java.nio.file.Path

/**
 * Save and load [Graph] instances using WebGraph compression.
 *
 * The graph is stored as:
 * - BVGraph files for adjacency structure (forward + backward per edge type)
 * - Binary files for node data, edge metadata, and graph metadata
 */
object GraphStore {

    private const val NODES_FILE = "nodes.bin"
    private const val EDGES_FILE = "edges.bin"
    private const val METADATA_FILE = "metadata.bin"
    private const val FORWARD_GRAPH = "forward"
    private const val BACKWARD_GRAPH = "backward"

    /**
     * Save a graph to disk in WebGraph format.
     */
    fun save(graph: Graph, dir: Path) {
        Files.createDirectories(dir)

        // 1. Collect all nodes and find max node ID for graph sizing
        val allNodes = mutableListOf<Node>()
        val maxNodeId = collectNodes(graph, allNodes)

        // 2. Collect all edges
        val allEdges = mutableListOf<Edge>()
        val forwardAdj = ArrayListMutableGraph(maxNodeId + 1)

        for (node in allNodes) {
            for (edge in graph.outgoing(node.id)) {
                allEdges.add(edge)
                try {
                    forwardAdj.addArc(edge.from.value, edge.to.value)
                } catch (e: IllegalArgumentException) {
                    // Duplicate arc - skip
                }
            }
        }

        // 3. Save BVGraph (forward)
        val forwardImmutable = forwardAdj.immutableView()
        BVGraph.store(forwardImmutable, dir.resolve(FORWARD_GRAPH).toString())

        // 4. Save BVGraph (backward = transpose)
        val backward = Transform.transpose(forwardImmutable)
        BVGraph.store(backward, dir.resolve(BACKWARD_GRAPH).toString())

        // 5. Save nodes
        NodeSerializer.saveNodes(allNodes.asSequence(), dir.resolve(NODES_FILE))

        // 6. Save edges (with type info)
        NodeSerializer.saveEdges(allEdges, dir.resolve(EDGES_FILE))

        // 7. Save metadata
        val metadata = collectMetadata(graph)
        NodeSerializer.saveMetadata(metadata, dir.resolve(METADATA_FILE))
    }

    /**
     * Load a graph from disk.
     */
    fun load(dir: Path): Graph {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }

        val forward = BVGraph.load(dir.resolve(FORWARD_GRAPH).toString())
        val backward = BVGraph.load(dir.resolve(BACKWARD_GRAPH).toString())
        val nodes = NodeSerializer.loadNodes(dir.resolve(NODES_FILE))
        val edges = NodeSerializer.loadEdges(dir.resolve(EDGES_FILE))
        val metadata = NodeSerializer.loadMetadata(dir.resolve(METADATA_FILE))

        return WebGraphBackedGraph(forward, backward, nodes, edges, metadata)
    }

    private fun collectNodes(graph: Graph, result: MutableList<Node>): Int {
        var maxId = 0
        for (node in graph.nodes(Node::class.java)) {
            result.add(node)
            if (node.id.value > maxId) maxId = node.id.value
        }
        return maxId
    }

    private fun collectMetadata(graph: Graph): GraphMetadata {
        // Collect type hierarchy
        val supertypes = mutableMapOf<String, Set<TypeDescriptor>>()
        val subtypes = mutableMapOf<String, Set<TypeDescriptor>>()

        // Walk all types referenced in the graph (nodes + methods)
        val allTypes = mutableSetOf<TypeDescriptor>()
        graph.nodes(Node::class.java).forEach { node ->
            when (node) {
                is LocalVariable -> {
                    allTypes.add(node.type)
                    allTypes.add(node.method.declaringClass)
                }
                is FieldNode -> {
                    allTypes.add(node.descriptor.declaringClass)
                    allTypes.add(node.descriptor.type)
                }
                is ParameterNode -> {
                    allTypes.add(node.type)
                    allTypes.add(node.method.declaringClass)
                }
                is ReturnNode -> {
                    node.actualType?.let { allTypes.add(it) }
                    allTypes.add(node.method.declaringClass)
                    allTypes.add(node.method.returnType)
                }
                is CallSiteNode -> {
                    allTypes.add(node.callee.declaringClass)
                    allTypes.add(node.callee.returnType)
                    allTypes.add(node.caller.declaringClass)
                }
                is EnumConstant -> allTypes.add(node.enumType)
                else -> {}
            }
        }
        // Also collect types from registered methods
        graph.methods(MethodPattern()).forEach { method ->
            allTypes.add(method.declaringClass)
            allTypes.add(method.returnType)
            method.parameterTypes.forEach { allTypes.add(it) }
        }

        // Include all types that have hierarchy info (covers types not referenced by nodes)
        graph.typeHierarchyTypes().forEach { allTypes.add(TypeDescriptor(it)) }

        for (type in allTypes) {
            val sups = graph.supertypes(type).toSet()
            if (sups.isNotEmpty()) supertypes[type.className] = sups
            val subs = graph.subtypes(type).toSet()
            if (subs.isNotEmpty()) subtypes[type.className] = subs
        }

        // Collect methods
        val methods = graph.methods(MethodPattern())
            .associateBy { it.signature }

        // Collect enum values - we can't enumerate all enum keys from Graph interface,
        // so we extract from EnumConstant nodes
        val enumValues = mutableMapOf<String, List<Any?>>()
        graph.nodes(EnumConstant::class.java).forEach { ec ->
            val key = "${ec.enumType.className}#${ec.enumName}"
            if (key !in enumValues) {
                val values = graph.enumValues(ec.enumType.className, ec.enumName)
                if (values != null) enumValues[key] = values
            }
        }

        // Collect member annotations - extract from all classes referenced in nodes
        val memberAnnotations = mutableMapOf<String, Map<String, Map<String, Any?>>>()
        val classMembers = mutableSetOf<Pair<String, String>>()
        graph.nodes(Node::class.java).forEach { node ->
            when (node) {
                is FieldNode -> classMembers.add(node.descriptor.declaringClass.className to node.descriptor.name)
                is CallSiteNode -> classMembers.add(node.callee.declaringClass.className to node.callee.name)
                is ParameterNode -> classMembers.add(node.method.declaringClass.className to node.method.name)
                is ReturnNode -> classMembers.add(node.method.declaringClass.className to node.method.name)
                else -> {}
            }
        }
        // Also add <class> level annotations
        val allClasses = classMembers.map { it.first }.toSet()
        for (className in allClasses) {
            classMembers.add(className to "<class>")
        }
        for ((className, memberName) in classMembers) {
            val annotations = graph.memberAnnotations(className, memberName)
            if (annotations.isNotEmpty()) {
                memberAnnotations["$className#$memberName"] = annotations
            }
        }

        // Collect branch scopes
        val branchScopes = graph.branchScopes().map { bs ->
            BranchScopeData(
                conditionNodeId = bs.conditionNodeId.value,
                method = bs.method,
                comparison = bs.comparison,
                trueBranchNodeIds = bs.trueBranchNodeIds.toIntArray(),
                falseBranchNodeIds = bs.falseBranchNodeIds.toIntArray()
            )
        }.toList()

        return GraphMetadata(
            methods = methods,
            supertypes = supertypes,
            subtypes = subtypes,
            enumValues = enumValues,
            memberAnnotations = memberAnnotations,
            branchScopes = branchScopes
        )
    }
}
