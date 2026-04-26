package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A [Graph] implementation backed by disk files.
 *
 * Node and edge data are read on demand from `nodes.dat` and `edges.dat`
 * via [RandomAccessFile.seek].  Only lightweight indexes (nodeId -> file offset,
 * nodeType -> nodeIds) are held in memory.
 *
 * **Memory profile (5.9M nodes, 6.5M edges):**
 * - Node index: ~47 MB (`LongArray`, 8 bytes per slot x 5.9M)
 * - Edge indexes: ~132 MB (`LongArray` offsets + `IntArray` starts)
 * - Type index: ~24 MB (`IntArray` node ids)
 * - Total: ~200 MB without per-entry `HashMap` / `MutableList` object overhead
 *
 * Created by [MmapGraphBuilder.build].
 */
class MmapGraph internal constructor(
    private val dataDir: Path,
    private val nodeIndex: LongArray,
    private val nodeTypeIndex: Map<Class<out Node>, IntArray>,
    private val outgoingIndex: EdgeOffsetIndex,
    private val incomingIndex: EdgeOffsetIndex,
    private val nodeMethods: List<MethodDescriptor>,
    private val methodIndex: Map<String, MethodDescriptor>,
    private val typeHierarchy: TypeHierarchy,
    private val enumValuesMap: Map<String, List<Any?>>,
    private val classOriginsMap: Map<String, String>,
    private val artifactDependenciesMap: Map<String, Map<String, Int>>,
    private val memberAnnotationsMap: Map<String, Map<String, Map<String, Any?>>>,
    private val branchScopeData: List<DefaultGraph.RawBranchScope>,
    override val resources: ResourceAccessor
) : Graph, Closeable {

    private val nodeMmap: ByteBuffer = FileChannel.open(dataDir.resolve("nodes.dat"), StandardOpenOption.READ).use {
        it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
    }
    private val edgeMmap: ByteBuffer = FileChannel.open(dataDir.resolve("edges.dat"), StandardOpenOption.READ).use {
        it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
    }

    private val branchScopeIndex: Map<Int, List<BranchScope>> by lazy {
        branchScopeData.map { raw ->
            BranchScope(
                conditionNodeId = NodeId(raw.conditionNodeId),
                method = raw.method,
                comparison = raw.comparison,
                trueBranchNodeIds = IntOpenHashSet(raw.trueBranchNodeIds),
                falseBranchNodeIds = IntOpenHashSet(raw.falseBranchNodeIds)
            )
        }.groupBy { it.conditionNodeId.value }
    }

    internal data class EdgeOffsetIndex(
        val starts: IntArray,
        val offsets: LongArray
    )

    override fun node(id: NodeId): Node? {
        val nodeId = id.value
        if (nodeId < 0 || nodeId >= nodeIndex.size) return null
        val offset = nodeIndex[nodeId]
        if (offset < 0L) return null
        return readNodeAt(offset)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
        // Fast path: exact type match
        nodeTypeIndex[type]?.let { ids ->
            return ids.asSequence().mapNotNull { node(NodeId(it)) as? T }
        }
        // Slow path: supertype match
        return nodeTypeIndex.entries.asSequence()
            .filter { type.isAssignableFrom(it.key) }
            .flatMap { it.value.asSequence() }
            .mapNotNull { node(NodeId(it)) as? T }
    }

    override fun outgoing(id: NodeId): Sequence<Edge> {
        return edgeOffsets(outgoingIndex, id).map { readEdgeAt(it) }
    }

    override fun incoming(id: NodeId): Sequence<Edge> {
        return edgeOffsets(incomingIndex, id).map { readEdgeAt(it) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> =
        outgoing(id).filter { type.isInstance(it) } as Sequence<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T : Edge> incoming(id: NodeId, type: Class<T>): Sequence<T> =
        incoming(id).filter { type.isInstance(it) } as Sequence<T>

    override fun callSites(methodPattern: MethodPattern): Sequence<CallSiteNode> =
        nodes(CallSiteNode::class.java).filter { methodPattern.matches(it.callee) }

    override fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        typeHierarchy.supertypes(type)

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        typeHierarchy.subtypes(type)

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
        methodIndex.values.asSequence().filter { pattern.matches(it) }

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        enumValuesMap["$enumClass#$enumName"]

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        memberAnnotationsMap["$className#$memberName"] ?: emptyMap()

    override fun classOrigin(className: String): String? = classOriginsMap[className]

    override fun classOrigins(): Map<String, String> = classOriginsMap

    override fun artifactDependencies(): Map<String, Map<String, Int>> = artifactDependenciesMap

    override fun branchScopes(): Sequence<BranchScope> =
        branchScopeIndex.values.asSequence().flatMap { it.asSequence() }

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        branchScopeIndex[conditionNodeId.value]?.asSequence() ?: emptySequence()

    override fun typeHierarchyTypes(): Set<String> = typeHierarchy.allKeys()

    override fun close() {
        // MappedByteBuffer is unmapped by GC; no explicit unmap in standard API
    }

    private fun readNodeAt(offset: Long): Node {
        val buf = nodeMmap.duplicate()
        buf.position(offset.toInt())
        val len = buf.getInt()
        val bytes = ByteArray(len)
        buf.get(bytes)
        return MmapGraphBuilder.deserializeNode(bytes, nodeMethods)
    }

    private fun readEdgeAt(offset: Long): Edge {
        val buf = edgeMmap.duplicate()
        buf.position(offset.toInt())
        val dis = DataInputStream(ByteBufferInputStream(buf))
        return MmapGraphBuilder.deserializeEdge(dis)
    }

    private fun edgeOffsets(index: EdgeOffsetIndex, id: NodeId): Sequence<Long> {
        val nodeId = id.value
        if (nodeId < 0 || nodeId + 1 >= index.starts.size) return emptySequence()
        val start = index.starts[nodeId]
        val end = index.starts[nodeId + 1]
        if (start == end) return emptySequence()
        return (start until end).asSequence().map { position -> index.offsets[position] }
    }

    internal class ByteBufferInputStream(private val buf: ByteBuffer) : InputStream() {
        override fun read(): Int = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!buf.hasRemaining()) return -1
            val n = minOf(len, buf.remaining())
            buf.get(b, off, n)
            return n
        }
    }
}
