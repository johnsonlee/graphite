package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import java.io.*
import java.nio.file.Path

/**
 * Serializes and deserializes graph nodes and metadata to/from binary files.
 */
internal object NodeSerializer {

    fun saveNodes(nodes: Sequence<Node>, file: Path) {
        ObjectOutputStream(BufferedOutputStream(file.toFile().outputStream())).use { oos ->
            val list = nodes.toList()
            oos.writeInt(list.size)
            for (node in list) {
                oos.writeObject(node)
            }
        }
    }

    fun loadNodes(file: Path): Map<Int, Node> {
        val result = mutableMapOf<Int, Node>()
        ObjectInputStream(BufferedInputStream(file.toFile().inputStream())).use { ois ->
            val count = ois.readInt()
            repeat(count) {
                val node = ois.readObject() as Node
                result[node.id.value] = node
            }
        }
        return result
    }

    fun saveEdges(edges: List<Edge>, file: Path) {
        ObjectOutputStream(BufferedOutputStream(file.toFile().outputStream())).use { oos ->
            oos.writeInt(edges.size)
            for (edge in edges) {
                oos.writeObject(edge)
            }
        }
    }

    fun loadEdges(file: Path): List<Edge> {
        val result = mutableListOf<Edge>()
        ObjectInputStream(BufferedInputStream(file.toFile().inputStream())).use { ois ->
            val count = ois.readInt()
            repeat(count) {
                result.add(ois.readObject() as Edge)
            }
        }
        return result
    }

    fun saveMetadata(metadata: GraphMetadata, file: Path) {
        ObjectOutputStream(BufferedOutputStream(file.toFile().outputStream())).use { oos ->
            oos.writeObject(metadata)
        }
    }

    fun loadMetadata(file: Path): GraphMetadata {
        ObjectInputStream(BufferedInputStream(file.toFile().inputStream())).use { ois ->
            return ois.readObject() as GraphMetadata
        }
    }
}

/**
 * Holds all non-graph metadata that doesn't fit in the adjacency structure.
 */
data class GraphMetadata(
    val methods: Map<String, MethodDescriptor>,
    val supertypes: Map<String, Set<TypeDescriptor>>,
    val subtypes: Map<String, Set<TypeDescriptor>>,
    val enumValues: Map<String, List<Any?>>,
    val memberAnnotations: Map<String, Map<String, Map<String, Any?>>>,
    val branchScopes: List<BranchScopeData>
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class BranchScopeData(
    val conditionNodeId: Int,
    val method: MethodDescriptor,
    val comparison: BranchComparison,
    val trueBranchNodeIds: IntArray,
    val falseBranchNodeIds: IntArray
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
