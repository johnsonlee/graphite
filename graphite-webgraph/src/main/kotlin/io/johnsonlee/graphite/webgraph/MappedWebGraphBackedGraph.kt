package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.webgraph.ImmutableGraph
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.util.LinkedHashMap

/**
 * A [Graph] backed by WebGraph compression for edges and memory-mapped I/O for nodes.
 *
 * Unlike [WebGraphBackedGraph] which deserializes all nodes into heap, this
 * implementation memory-maps the `graph.nodedata` file. The OS manages paging:
 * accessed nodes are cached in physical RAM via the page cache, unused nodes
 * stay on disk. No JVM heap is used for node storage.
 *
 * Unlike a seek-based node reader, this uses [MappedByteBuffer] which translates
 * to direct memory reads -- no system calls after the initial page fault.
 *
 * **Memory profile (5.9M nodes, 6.5M edges):**
 * - BVGraph forward: loaded during graph open
 * - BVGraph backward: built lazily on first incoming query
 * - Edge label map: loaded during graph open
 * - Node offset index: mmap-backed, nodeId -> offset
 * - Node type index: mmap-backed, type -> nodeId list
 * - StringTable: ~21 MB (heap)
 * - Node data: ~252 MB (mmap, NOT heap — managed by OS page cache)
 * - **Open heap before edge traversal: ~92 MB plus object overhead** vs ~4 GB
 *   for eager [WebGraphBackedGraph]
 *
 * Created by [GraphStore.loadMapped].
 */
@Suppress("LongParameterList")
internal class MappedWebGraphBackedGraph(
    private val forward: ImmutableGraph,
    private val backward: Lazy<ImmutableGraph>,
    private val mappedNodeData: MappedByteBuffer,
    private val nodeDataVersion: Int,
    private val stringTable: StringTable,
    private val nodeOffsets: NodeOffsetIndex,
    private val nodeTypeIndex: NodeTypeIndex,
    private val forwardLabels: ByteArray,
    private val cumulativeOutdeg: IntArray,
    private val edgeCount: Long,
    private val metadataFile: File,
    private val methodCount: Long,
    private val comparisonLookup: BranchComparisonLookup,
    private val metadata: Lazy<GraphMetadata>,
    private val classOverviewProvider: (Int) -> ClassOverview?,
    private val resourceAccessor: Lazy<ResourceAccessor>
) : Graph, Closeable {

    override val resources: ResourceAccessor
        get() = resourceAccessor.value

    private val branchScopeIndex: Map<Int, List<BranchScope>> by lazy {
        metadata.value.branchScopes.map { raw ->
            BranchScope(
                conditionNodeId = NodeId(raw.conditionNodeId),
                method = raw.method,
                comparison = raw.comparison,
                trueBranchNodeIds = IntOpenHashSet(raw.trueBranchNodeIds),
                falseBranchNodeIds = IntOpenHashSet(raw.falseBranchNodeIds)
            )
        }.groupBy { it.conditionNodeId.value }
    }

    private val stringPropertyIndexLock = Any()
    private val stringPropertyAccesses = LinkedHashMap<StringPropertyKey, Int>()
    private val stringPropertyIndexes = object : LinkedHashMap<StringPropertyKey, MappedStringPropertyIndex>(
        MAX_STRING_PROPERTY_INDEXES + 1,
        STRING_PROPERTY_INDEX_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StringPropertyKey, MappedStringPropertyIndex>?): Boolean =
            size > MAX_STRING_PROPERTY_INDEXES
    }

    override fun node(id: NodeId): Node? {
        val nodeId = id.value
        if (nodeId < 0 || nodeId >= nodeOffsets.size) return null
        val offset = nodeOffsets.offset(nodeId)
        if (offset == -1L) return null
        return readNodeAt(offset)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
        return nodeTypeIndex.ids(type).mapNotNull { node(NodeId(it)) as? T }
    }

    override fun nodeCount(type: Class<out Node>): Long =
        nodeTypeIndex.count(type)

    @Suppress("UNCHECKED_CAST", "ReturnCount")
    override fun <T : Node> nodesByStringProperty(
        type: Class<T>,
        property: String,
        mode: StringMatchMode,
        expected: String
    ): Sequence<T>? {
        if (!supportsRawStringProperty(type, property)) return null
        val key = StringPropertyKey(type, property)
        val index = synchronized(stringPropertyIndexLock) {
            stringPropertyIndexes[key] ?: run {
                val accesses = (stringPropertyAccesses[key] ?: 0) + 1
                stringPropertyAccesses[key] = accesses
                trimStringPropertyAccesses()
                if (accesses < MIN_STRING_PROPERTY_INDEX_ACCESSES) {
                    null
                } else {
                    buildStringPropertyIndex(type, property).also { stringPropertyIndexes[key] = it }
                }
            }
        } ?: return null
        return index.matchingNodeIds(mode, expected)
            .mapNotNull { node(NodeId(it)) as? T }
    }

    override fun edgeCount(): Long = edgeCount

    override fun outgoing(id: NodeId): Sequence<Edge> {
        val nodeIdx = id.value
        if (nodeIdx >= forward.numNodes()) return emptySequence()
        val succs = forward.successorArray(nodeIdx)
        val outdeg = forward.outdegree(nodeIdx)
        val labelStart = cumulativeOutdeg[nodeIdx]
        return (0 until outdeg).asSequence().map { i ->
            val to = succs[i]
            val label = forwardLabels[labelStart + i].toInt() and BYTE_MASK
            val comparison = comparisonForEdge(label, nodeIdx, to, nodeDataVersion, comparisonLookup)
            NodeSerializer.decodeEdge(label, NodeId(nodeIdx), NodeId(to), comparison, nodeDataVersion)
        }
    }

    override fun incoming(id: NodeId): Sequence<Edge> {
        val nodeIdx = id.value
        val backwardGraph = backward.value
        if (nodeIdx >= backwardGraph.numNodes()) return emptySequence()
        val preds = backwardGraph.successorArray(nodeIdx)
        val indeg = backwardGraph.outdegree(nodeIdx)
        return (0 until indeg).asSequence().map { i ->
            val from = preds[i]
            val label = lookupForwardLabel(from, nodeIdx)
            val comparison = comparisonForEdge(label, from, nodeIdx, nodeDataVersion, comparisonLookup)
            NodeSerializer.decodeEdge(label, NodeId(from), NodeId(nodeIdx), comparison, nodeDataVersion)
        }
    }

    private fun lookupForwardLabel(from: Int, to: Int): Int {
        val succs = forward.successorArray(from)
        val outdeg = forward.outdegree(from)
        val pos = java.util.Arrays.binarySearch(succs, 0, outdeg, to)
        return if (pos >= 0) {
            forwardLabels[cumulativeOutdeg[from] + pos].toInt() and BYTE_MASK
        } else {
            0
        }
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
        metadata.value.supertypes[type.className]?.asSequence() ?: emptySequence()

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        metadata.value.subtypes[type.className]?.asSequence() ?: emptySequence()

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
        metadata.value.methods.values.asSequence().filter { pattern.matches(it) }

    override fun methodCount(): Long = methodCount

    override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> =
        DataInputStream(BufferedInputStream(metadataFile.inputStream())).use { dis ->
            NodeSerializer.readMetadataMethodSlice(dis, stringTable, pattern, limit)
        }

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        metadata.value.enumValues["$enumClass#$enumName"]

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        metadata.value.memberAnnotations["$className#$memberName"] ?: emptyMap()

    override fun memberAnnotationIndex(): Map<String, Map<String, Map<String, Any?>>> =
        metadata.value.memberAnnotations

    override fun classOrigin(className: String): String? = metadata.value.classOrigins[className]

    override fun classOrigins(): Map<String, String> = metadata.value.classOrigins

    override fun artifactDependencies(): Map<String, Map<String, Int>> = metadata.value.artifactDependencies

    override fun classOverview(limit: Int): ClassOverview? = classOverviewProvider(limit)

    override fun branchScopes(): Sequence<BranchScope> =
        branchScopeIndex.values.asSequence().flatMap { it.asSequence() }

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        branchScopeIndex[conditionNodeId.value]?.asSequence() ?: emptySequence()

    override fun typeHierarchyTypes(): Set<String> =
        metadata.value.supertypes.keys + metadata.value.subtypes.keys

    override fun close() {
        clearStringPropertyIndexes()
        // MappedByteBuffer is unmapped by GC; no explicit unmap in standard API
    }

    internal fun clearStringPropertyIndexes() {
        synchronized(stringPropertyIndexLock) {
            stringPropertyIndexes.clear()
            stringPropertyAccesses.clear()
        }
    }

    private fun trimStringPropertyAccesses() {
        while (stringPropertyAccesses.size > MAX_STRING_PROPERTY_ACCESS_COUNTS) {
            val oldest = stringPropertyAccesses.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private fun buildStringPropertyIndex(
        type: Class<out Node>,
        property: String
    ): MappedStringPropertyIndex {
        val capacity = nodeTypeIndex.count(type).toInt()
        val nodeIds = IntArray(capacity)
        val stringIds = IntArray(capacity)
        val uniqueStringIds = HashSet<Int>()
        var size = 0
        for (nodeId in nodeTypeIndex.ids(type)) {
            val stringId = rawStringPropertyIndex(nodeId, type, property) ?: continue
            nodeIds[size] = nodeId
            stringIds[size] = stringId
            uniqueStringIds.add(stringId)
            size++
        }
        return MappedStringPropertyIndex(
            nodeIds.copyOf(size),
            stringIds.copyOf(size),
            uniqueStringIds.toIntArray().sortedArray(),
            stringTable
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun rawStringPropertyIndex(
        nodeId: Int,
        type: Class<out Node>,
        property: String
    ): Int? {
        val offset = nodeOffsets.offset(nodeId)
        if (offset < 0L) return null
        val fields = offset.toInt() + NODE_HEADER_BYTES
        return when (type) {
            StringConstant::class.java -> mappedNodeData.getInt(fields)
            EnumConstant::class.java -> when (property) {
                "enum_type" -> mappedNodeData.getInt(fields)
                "name" -> mappedNodeData.getInt(fields + Int.SIZE_BYTES)
                else -> null
            }
            LocalVariable::class.java -> when (property) {
                "name" -> mappedNodeData.getInt(fields)
                "type" -> mappedNodeData.getInt(fields + Int.SIZE_BYTES)
                else -> null
            }
            FieldNode::class.java -> when (property) {
                "class" -> mappedNodeData.getInt(fields)
                "name" -> mappedNodeData.getInt(fields + Int.SIZE_BYTES)
                "type" -> mappedNodeData.getInt(fields + 2 * Int.SIZE_BYTES)
                else -> null
            }
            ParameterNode::class.java -> if (property == "type") {
                mappedNodeData.getInt(fields + Int.SIZE_BYTES)
            } else {
                null
            }
            ResourceFileNode::class.java -> when (property) {
                "path" -> mappedNodeData.getInt(fields)
                "source" -> mappedNodeData.getInt(fields + Int.SIZE_BYTES)
                "format" -> mappedNodeData.getInt(fields + 2 * Int.SIZE_BYTES)
                else -> null
            }
            CallSiteNode::class.java -> rawCallSiteStringProperty(fields, property)
            else -> null
        }
    }

    private fun rawCallSiteStringProperty(fields: Int, property: String): Int? {
        val callerParameters = mappedNodeData.getInt(fields + 2 * Int.SIZE_BYTES)
        val calleeFields = fields + (METHOD_DESCRIPTOR_FIXED_INTS + callerParameters) * Int.SIZE_BYTES
        return when (property) {
            "caller_class" -> mappedNodeData.getInt(fields)
            "caller_name" -> mappedNodeData.getInt(fields + Int.SIZE_BYTES)
            "callee_class" -> mappedNodeData.getInt(calleeFields)
            "callee_name" -> mappedNodeData.getInt(calleeFields + Int.SIZE_BYTES)
            else -> null
        }
    }

    private fun readNodeAt(offset: Long): Node {
        val input = ByteBufferDataInput(mappedNodeData, offset.toInt())
        return NodeSerializer.readNode(input, stringTable, nodeDataVersion)
    }
}

private const val NODE_HEADER_BYTES = Int.SIZE_BYTES + Byte.SIZE_BYTES
private const val METHOD_DESCRIPTOR_FIXED_INTS = 4
private const val MAX_STRING_PROPERTY_INDEXES = 4
private const val MAX_STRING_PROPERTY_ACCESS_COUNTS = 16
private const val MIN_STRING_PROPERTY_INDEX_ACCESSES = 2
private const val MAX_STRING_MATCH_CACHE_ENTRIES = 32
private const val MAX_CACHED_MATCHING_STRINGS = 100_000
private const val MAX_TRIGRAM_INDEX_STRINGS = 500_000
private const val MIN_TRIGRAM_LENGTH = 3
private const val STRING_PROPERTY_INDEX_LOAD_FACTOR = 0.75f
private const val STRING_HASH_FACTOR = 31

private data class StringPropertyKey(
    val type: Class<out Node>,
    val property: String
)

private data class StringMatchKey(
    val mode: StringMatchMode,
    val expected: String
)

private class MappedStringPropertyIndex(
    private val nodeIds: IntArray,
    private val stringIds: IntArray,
    private val uniqueStringIds: IntArray,
    private val stringTable: StringTable
) {
    private val trigramIndex: Int2ObjectOpenHashMap<IntArray>? by lazy(::buildTrigramIndex)
    private val matchingStrings = object : LinkedHashMap<StringMatchKey, IntArray>(
        MAX_STRING_MATCH_CACHE_ENTRIES + 1,
        STRING_PROPERTY_INDEX_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StringMatchKey, IntArray>?): Boolean =
            size > MAX_STRING_MATCH_CACHE_ENTRIES
    }

    fun matchingNodeIds(mode: StringMatchMode, expected: String): Sequence<Int> {
        val matchedStrings = matchingStringIds(mode, expected)
        if (matchedStrings.isEmpty()) return emptySequence()
        return nodeIds.indices.asSequence()
            .filter { java.util.Arrays.binarySearch(matchedStrings, stringIds[it]) >= 0 }
            .map { nodeIds[it] }
    }

    @Synchronized
    private fun matchingStringIds(mode: StringMatchMode, expected: String): IntArray {
        val key = StringMatchKey(mode, expected)
        matchingStrings[key]?.let { return it }

        val result = when {
            mode == StringMatchMode.CONTAINS && expected.length >= MIN_TRIGRAM_LENGTH ->
                matchingContainsStringIds(expected)
            else -> scanMatchingStringIds(mode, expected)
        }
        if (result.size <= MAX_CACHED_MATCHING_STRINGS) matchingStrings[key] = result
        return result
    }

    @Suppress("ReturnCount")
    private fun matchingContainsStringIds(expected: String): IntArray {
        val index = trigramIndex ?: return scanMatchingStringIds(StringMatchMode.CONTAINS, expected)
        var candidates: IntArray? = null
        val seenTrigrams = IntOpenHashSet()
        for (position in 0..expected.length - MIN_TRIGRAM_LENGTH) {
            val trigram = trigramHash(expected, position)
            if (!seenTrigrams.add(trigram)) continue
            val posting = index[trigram] ?: return IntArray(0)
            if (candidates == null || posting.size < candidates.size) candidates = posting
        }

        val shortestPosting = candidates ?: return IntArray(0)
        val matched = IntArray(shortestPosting.size)
        var size = 0
        for (stringId in shortestPosting) {
            if (stringTable.get(stringId).contains(expected)) matched[size++] = stringId
        }
        return matched.copyOf(size).also(java.util.Arrays::sort)
    }

    private fun scanMatchingStringIds(mode: StringMatchMode, expected: String): IntArray {
        val matched = IntArray(uniqueStringIds.size)
        var size = 0
        for (stringId in uniqueStringIds) {
            val actual = stringTable.get(stringId)
            val matches = when (mode) {
                StringMatchMode.STARTS_WITH -> actual.startsWith(expected)
                StringMatchMode.ENDS_WITH -> actual.endsWith(expected)
                StringMatchMode.CONTAINS -> actual.contains(expected)
            }
            if (matches) matched[size++] = stringId
        }
        return matched.copyOf(size)
    }

    private fun buildTrigramIndex(): Int2ObjectOpenHashMap<IntArray>? {
        if (uniqueStringIds.size > MAX_TRIGRAM_INDEX_STRINGS) return null
        val builders = Int2ObjectOpenHashMap<IntArrayList>()
        val seenTrigrams = IntOpenHashSet()
        for (stringId in uniqueStringIds) {
            val value = stringTable.get(stringId)
            seenTrigrams.clear()
            for (position in 0..value.length - MIN_TRIGRAM_LENGTH) {
                val trigram = trigramHash(value, position)
                if (seenTrigrams.add(trigram)) {
                    builders.computeIfAbsent(trigram) { IntArrayList() }.add(stringId)
                }
            }
        }

        val result = Int2ObjectOpenHashMap<IntArray>(builders.size)
        builders.int2ObjectEntrySet().forEach { entry ->
            result.put(entry.intKey, entry.value.toIntArray())
        }
        return result
    }
}

private fun trigramHash(value: String, position: Int): Int =
    (value[position].code * STRING_HASH_FACTOR + value[position + 1].code) * STRING_HASH_FACTOR +
        value[position + 2].code

@Suppress("CyclomaticComplexMethod")
private fun supportsRawStringProperty(type: Class<out Node>, property: String): Boolean = when (type) {
    StringConstant::class.java -> property == "value"
    EnumConstant::class.java -> property == "enum_type" || property == "name"
    LocalVariable::class.java -> property == "name" || property == "type"
    FieldNode::class.java -> property == "class" || property == "name" || property == "type"
    ParameterNode::class.java -> property == "type"
    ResourceFileNode::class.java -> property == "path" || property == "source" || property == "format"
    CallSiteNode::class.java -> property == "caller_class" || property == "caller_name" ||
        property == "callee_class" || property == "callee_name"
    else -> false
}

/**
 * Adapts a [ByteBuffer] as [DataInput] for node deserialization.
 * No system calls -- reads directly from mapped memory.
 */
private class ByteBufferDataInput(private val buf: ByteBuffer, private var position: Int) : DataInput {

    override fun readFully(bytes: ByteArray) {
        readFully(bytes, 0, bytes.size)
    }

    override fun readFully(bytes: ByteArray, off: Int, len: Int) {
        requireRemaining(len)
        for (i in 0 until len) {
            bytes[off + i] = buf.get(position + i)
        }
        position += len
    }

    override fun skipBytes(n: Int): Int {
        if (n <= 0) return 0
        val skipped = minOf(n, buf.limit() - position)
        position += skipped
        return skipped
    }

    override fun readBoolean(): Boolean = readUnsignedByte() != 0

    override fun readByte(): Byte {
        requireRemaining(Byte.SIZE_BYTES)
        return buf.get(position++)
    }

    override fun readUnsignedByte(): Int = readByte().toInt() and BYTE_MASK

    override fun readShort(): Short {
        requireRemaining(Short.SIZE_BYTES)
        val value = buf.getShort(position)
        position += Short.SIZE_BYTES
        return value
    }

    override fun readUnsignedShort(): Int = readShort().toInt() and USHORT_MASK

    override fun readChar(): Char {
        requireRemaining(Char.SIZE_BYTES)
        val value = buf.getChar(position)
        position += Char.SIZE_BYTES
        return value
    }

    override fun readInt(): Int {
        requireRemaining(Int.SIZE_BYTES)
        val value = buf.getInt(position)
        position += Int.SIZE_BYTES
        return value
    }

    override fun readLong(): Long {
        requireRemaining(Long.SIZE_BYTES)
        val value = buf.getLong(position)
        position += Long.SIZE_BYTES
        return value
    }

    override fun readFloat(): Float {
        requireRemaining(Float.SIZE_BYTES)
        val value = buf.getFloat(position)
        position += Float.SIZE_BYTES
        return value
    }

    override fun readDouble(): Double {
        requireRemaining(Double.SIZE_BYTES)
        val value = buf.getDouble(position)
        position += Double.SIZE_BYTES
        return value
    }

    override fun readLine(): String =
        throw UnsupportedOperationException("ByteBufferDataInput does not support readLine")

    override fun readUTF(): String = DataInputStream.readUTF(this)

    private fun requireRemaining(bytes: Int) {
        if (position < 0 || position > buf.limit() - bytes) throw EOFException()
    }

    private companion object {
        private const val USHORT_MASK = 0xFFFF
    }
}
