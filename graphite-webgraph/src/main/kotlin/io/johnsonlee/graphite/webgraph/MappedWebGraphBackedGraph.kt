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
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.WorkAwareTransformedStringPropertyLookup
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyLookup
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
@Suppress("LongParameterList", "TooManyFunctions")
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
) : Graph,
    WorkAwareStringPropertyLookup,
    WorkAwareTransformedStringPropertyLookup,
    StringPropertyLookupOrder,
    Closeable {

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
    private val stringPropertyAdmissions = StringPropertyAdmissions()
    @Volatile
    private var stringPropertyNodeOrders: IntArray? = null
    private val stringPropertyIndexes = object : LinkedHashMap<StringPropertyKey, MappedStringPropertyIndex>(
        MAX_STRING_PROPERTY_INDEXES + 1,
        STRING_PROPERTY_INDEX_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<StringPropertyKey, MappedStringPropertyIndex>?
        ): Boolean {
            val remove = size > MAX_STRING_PROPERTY_INDEXES
            if (remove && eldest != null) stringPropertyAdmissions.clear(eldest.key)
            return remove
        }
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

    override fun stringPropertyNodeOrder(node: Node): Long {
        val orders = stringPropertyNodeOrders ?: synchronized(this) {
            stringPropertyNodeOrders ?: buildStringPropertyNodeOrders().also { stringPropertyNodeOrders = it }
        }
        return orders.getOrElse(node.id.value) { -1 }.toLong()
    }

    private fun buildStringPropertyNodeOrders(): IntArray {
        val orders = IntArray(nodeOffsets.size) { -1 }
        var order = 0
        for (nodeId in nodeTypeIndex.ids(Node::class.java)) orders[nodeId] = order++
        return orders
    }

    @Suppress("UNCHECKED_CAST", "ReturnCount")
    override fun <T : Node> nodesByStringProperty(
        type: Class<T>,
        property: String,
        mode: StringMatchMode,
        expected: String,
        limit: Int
    ): Sequence<T>? = lookupStringProperty(type, property, null, mode, expected, limit, workConsumer = null)

    override fun <T : Node> nodesByStringProperty(
        type: Class<T>,
        property: String,
        mode: StringMatchMode,
        expected: String,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): Sequence<T>? = lookupStringProperty(type, property, null, mode, expected, limit, workConsumer)

    override fun <T : Node> nodesByTransformedStringProperty(
        type: Class<T>,
        property: String,
        transform: StringValueTransform,
        mode: StringMatchMode,
        expected: String,
        limit: Int
    ): Sequence<T>? = lookupStringProperty(type, property, transform, mode, expected, limit, workConsumer = null)

    override fun <T : Node> nodesByTransformedStringProperty(
        type: Class<T>,
        property: String,
        transform: StringValueTransform,
        mode: StringMatchMode,
        expected: String,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): Sequence<T>? = lookupStringProperty(type, property, transform, mode, expected, limit, workConsumer)

    @Suppress("UNCHECKED_CAST", "ReturnCount")
    private fun <T : Node> lookupStringProperty(
        type: Class<T>,
        property: String,
        transform: StringValueTransform?,
        mode: StringMatchMode,
        expected: String,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): Sequence<T>? {
        if (!supportsRawStringProperty(type, property)) return null
        if (limit <= 0) return emptySequence()
        val key = StringPropertyKey(type, property)
        synchronized(stringPropertyIndexLock) {
            stringPropertyIndexes[key]
        }?.let { index ->
            return indexedNodes(index, transform, mode, expected, limit, workConsumer)
        }

        val admission = StringPropertyAdmissionKey(key, transform, mode, expected, limit)
        val shouldBuild = synchronized(stringPropertyIndexLock) {
            stringPropertyAdmissions.shouldBuild(admission, limit == Int.MAX_VALUE)
        }
        if (!shouldBuild) {
            return if (limit == Int.MAX_VALUE) {
                null
            } else {
                rawStringPropertyScan(type, property, transform, mode, expected, limit, admission, workConsumer)
            }
        }

        val index = synchronized(stringPropertyIndexLock) {
            stringPropertyIndexes[key] ?: run {
                buildStringPropertyIndex(type, property, workConsumer)?.also {
                    stringPropertyIndexes[key] = it
                    stringPropertyAdmissions.clear(key)
                } ?: run {
                    stringPropertyAdmissions.reject(key)
                    null
                }
            }
        } ?: return if (limit == Int.MAX_VALUE) {
            null
        } else {
            rawStringPropertyScan(type, property, transform, mode, expected, limit, admission, workConsumer)
        }
        return indexedNodes(index, transform, mode, expected, limit, workConsumer)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Node> indexedNodes(
        index: MappedStringPropertyIndex,
        transform: StringValueTransform?,
        mode: StringMatchMode,
        expected: String,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): Sequence<T> = index.matchingNodeIds(transform, mode, expected, workConsumer)
        .take(limit)
        .mapNotNull { node(NodeId(it)) as? T }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Node> rawStringPropertyScan(
        type: Class<T>,
        property: String,
        transform: StringValueTransform?,
        mode: StringMatchMode,
        expected: String,
        limit: Int,
        admission: StringPropertyAdmissionKey,
        workConsumer: GraphWorkConsumer?
    ): Sequence<T> = sequence {
        var inspected = 0
        var yielded = 0
        var admitted = false
        var matchStates: ByteArray? = null
        for (nodeId in nodeTypeIndex.ids(type)) {
            workConsumer?.consume()
            inspected++
            if (!admitted && inspected > MAX_STRING_PROPERTY_ADMISSION_NODES) {
                synchronized(stringPropertyIndexLock) {
                    if (admission.property !in stringPropertyIndexes) {
                        stringPropertyAdmissions.admit(admission)
                    }
                }
                admitted = true
                val stringCount = stringTable.size()
                if (stringCount <= MAX_RAW_STRING_MATCH_STATE_BYTES) {
                    matchStates = ByteArray(stringCount)
                }
            }
            val stringId = rawStringPropertyIndex(nodeId, type, property)
            val states = matchStates
            val matches = if (stringId == null) {
                false
            } else if (states == null) {
                stringMatches(stringTable.get(stringId), transform, mode, expected)
            } else {
                when (states[stringId]) {
                    RAW_STRING_MATCH -> true
                    RAW_STRING_MISS -> false
                    else -> stringMatches(stringTable.get(stringId), transform, mode, expected).also { matched ->
                        states[stringId] = if (matched) RAW_STRING_MATCH else RAW_STRING_MISS
                    }
                }
            }
            if (matches) {
                val matchedNode = node(NodeId(nodeId)) as? T
                if (matchedNode != null) {
                    yield(matchedNode)
                    yielded++
                    if (yielded >= limit) break
                }
            }
        }
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
        stringPropertyNodeOrders = null
        // MappedByteBuffer is unmapped by GC; no explicit unmap in standard API
    }

    internal fun clearStringPropertyIndexes() {
        synchronized(stringPropertyIndexLock) {
            stringPropertyIndexes.clear()
            stringPropertyAdmissions.clear()
        }
    }

    internal fun stringPropertyIndexCount(
        type: Class<out Node>? = null,
        property: String? = null
    ): Int = synchronized(stringPropertyIndexLock) {
        stringPropertyIndexes.keys.count { key ->
            (type == null || key.type == type) && (property == null || key.property == property)
        }
    }

    private fun buildStringPropertyIndex(
        type: Class<out Node>,
        property: String,
        workConsumer: GraphWorkConsumer?
    ): MappedStringPropertyIndex? {
        val nodeCount = nodeTypeIndex.count(type)
        if (estimatedStringPropertyIndexBytes(nodeCount) > MAX_STRING_PROPERTY_INDEX_RETAINED_BYTES) return null
        val capacity = nodeCount.toInt()
        val nodeIds = IntArray(capacity)
        val stringIds = IntArray(capacity)
        var size = 0
        for (nodeId in nodeTypeIndex.ids(type)) {
            workConsumer?.consume()
            val stringId = rawStringPropertyIndex(nodeId, type, property) ?: continue
            nodeIds[size] = nodeId
            stringIds[size] = stringId
            size++
        }
        val indexedNodeIds = if (size == capacity) nodeIds else nodeIds.copyOf(size)
        val indexedStringIds = if (size == capacity) stringIds else stringIds.copyOf(size)
        val uniqueStringIds = indexedStringIds.copyOf().apply(java.util.Arrays::sort)
        var uniqueSize = 0
        for (stringId in uniqueStringIds) {
            if (uniqueSize == 0 || uniqueStringIds[uniqueSize - 1] != stringId) {
                uniqueStringIds[uniqueSize++] = stringId
            }
        }
        return MappedStringPropertyIndex(
            indexedNodeIds,
            indexedStringIds,
            uniqueStringIds.copyOf(uniqueSize),
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
private const val MAX_STRING_PROPERTY_ADMISSION_NODES = 256
private const val MAX_STRING_PROPERTY_ADMISSIONS = 32
private const val MAX_STRING_PROPERTY_ADMISSION_BYTES = 64L * 1024
private const val STRING_PROPERTY_ADMISSION_ESTIMATED_BYTES = 96L
private const val MAX_STRING_PROPERTY_INDEX_RETAINED_BYTES = 8L * 1024 * 1024
private const val MAX_RAW_STRING_MATCH_STATE_BYTES = 16 * 1024 * 1024
private const val STRING_PROPERTY_INDEX_ARRAYS = 3
private const val PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES = 16L
private const val MAX_STRING_MATCH_CACHE_ENTRIES = 32
private const val MAX_STRING_MATCH_CACHE_BYTES = 2L * 1024 * 1024
private const val STRING_MATCH_CACHE_ENTRY_ESTIMATED_BYTES = 64L
private const val STRING_HEADER_ESTIMATED_BYTES = 24L
private const val MAX_TRIGRAM_INDEX_RETAINED_BYTES = 16L * 1024 * 1024
private const val MAX_TRIGRAM_POSTINGS = 1_000_000
private const val MAX_TRIGRAM_INDEX_STRINGS = 500_000
private const val TRIGRAM_ENTRY_ESTIMATED_BYTES = 64L
private const val TRIGRAM_POSTING_ESTIMATED_BYTES = 12L
private const val MIN_TRIGRAM_LENGTH = 3
private const val STRING_PROPERTY_INDEX_LOAD_FACTOR = 0.75f
private const val STRING_HASH_FACTOR = 31
private const val RAW_STRING_MISS: Byte = 1
private const val RAW_STRING_MATCH: Byte = 2

private fun estimatedStringPropertyIndexBytes(nodeCount: Long): Long =
    STRING_PROPERTY_INDEX_ARRAYS * PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES +
        nodeCount * STRING_PROPERTY_INDEX_ARRAYS * Int.SIZE_BYTES

private data class StringPropertyKey(
    val type: Class<out Node>,
    val property: String
)

private data class StringPropertyAdmissionKey(
    val property: StringPropertyKey,
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String,
    val limit: Int
)

private class StringPropertyAdmissions {
    private val predicates = LinkedHashMap<StringPropertyAdmissionKey, Unit>(
        MAX_STRING_PROPERTY_ADMISSIONS + 1,
        STRING_PROPERTY_INDEX_LOAD_FACTOR,
        true
    )
    private val unboundedProperties = mutableSetOf<StringPropertyKey>()
    private val rejectedProperties = mutableSetOf<StringPropertyKey>()
    private var retainedBytes = 0L

    fun shouldBuild(admission: StringPropertyAdmissionKey, unbounded: Boolean): Boolean {
        if (admission.property in rejectedProperties) return false
        return if (unbounded) {
            !unboundedProperties.add(admission.property)
        } else {
            predicates[admission] != null
        }
    }

    fun admit(admission: StringPropertyAdmissionKey) {
        if (admission.property in rejectedProperties || predicates[admission] != null) return
        val bytes = estimatedStringPropertyAdmissionBytes(admission)
        if (bytes > MAX_STRING_PROPERTY_ADMISSION_BYTES) return
        predicates[admission] = Unit
        retainedBytes += bytes
        while (predicates.size > MAX_STRING_PROPERTY_ADMISSIONS ||
            retainedBytes > MAX_STRING_PROPERTY_ADMISSION_BYTES
        ) {
            val entries = predicates.entries.iterator()
            val eldest = entries.next().key
            retainedBytes -= estimatedStringPropertyAdmissionBytes(eldest)
            entries.remove()
        }
    }

    fun reject(property: StringPropertyKey) {
        clear(property)
        rejectedProperties.add(property)
    }

    fun clear(property: StringPropertyKey) {
        val entries = predicates.entries.iterator()
        while (entries.hasNext()) {
            val admission = entries.next().key
            if (admission.property == property) {
                retainedBytes -= estimatedStringPropertyAdmissionBytes(admission)
                entries.remove()
            }
        }
        unboundedProperties.remove(property)
    }

    fun clear() {
        predicates.clear()
        unboundedProperties.clear()
        rejectedProperties.clear()
        retainedBytes = 0L
    }
}

private data class StringMatchKey(
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

internal class MappedStringPropertyIndex(
    private val nodeIds: IntArray,
    private val stringIds: IntArray,
    private val uniqueStringIds: IntArray,
    private val stringTable: StringTable,
    private val maxTrigramPostings: Int = MAX_TRIGRAM_POSTINGS,
    private val maxTrigramBytes: Long = MAX_TRIGRAM_INDEX_RETAINED_BYTES,
    private val maxMatchingStringCacheEntries: Int = MAX_STRING_MATCH_CACHE_ENTRIES,
    private val maxMatchingStringCacheBytes: Long = MAX_STRING_MATCH_CACHE_BYTES
) {
    private var trigramIndexInitialized = false
    private var trigramIndex: Int2ObjectOpenHashMap<IntArray>? = null
    private val matchingStrings = LinkedHashMap<StringMatchKey, IntArray>(
        MAX_STRING_MATCH_CACHE_ENTRIES + 1,
        STRING_PROPERTY_INDEX_LOAD_FACTOR,
        true
    )
    private var matchingStringCacheBytes = 0L

    fun matchingNodeIds(
        mode: StringMatchMode,
        expected: String
    ): Sequence<Int> = matchingNodeIds(null, mode, expected, workConsumer = null)

    fun matchingNodeIds(
        transform: StringValueTransform?,
        mode: StringMatchMode,
        expected: String,
        workConsumer: GraphWorkConsumer?
    ): Sequence<Int> {
        val matchedStrings = matchingStringIds(transform, mode, expected, workConsumer)
        if (matchedStrings.isEmpty()) return emptySequence()
        return sequence {
            for (index in nodeIds.indices) {
                workConsumer?.consume()
                if (java.util.Arrays.binarySearch(matchedStrings, stringIds[index]) >= 0) {
                    yield(nodeIds[index])
                }
            }
        }
    }

    @Synchronized
    private fun matchingStringIds(
        transform: StringValueTransform?,
        mode: StringMatchMode,
        expected: String,
        workConsumer: GraphWorkConsumer?
    ): IntArray {
        val key = StringMatchKey(transform, mode, expected)
        matchingStrings[key]?.let { return it }

        val result = when {
            transform == null && mode == StringMatchMode.CONTAINS && expected.length >= MIN_TRIGRAM_LENGTH ->
                matchingContainsStringIds(expected, workConsumer)
            else -> scanMatchingStringIds(transform, mode, expected, workConsumer)
        }
        cacheMatchingStrings(key, result)
        return result
    }

    private fun cacheMatchingStrings(key: StringMatchKey, result: IntArray) {
        val resultBytes = estimatedMatchingStringCacheBytes(key, result)
        if (resultBytes > maxMatchingStringCacheBytes) return
        matchingStrings[key] = result
        matchingStringCacheBytes += resultBytes
        while (matchingStrings.size > maxMatchingStringCacheEntries ||
            matchingStringCacheBytes > maxMatchingStringCacheBytes
        ) {
            val entries = matchingStrings.entries.iterator()
            val eldest = entries.next()
            matchingStringCacheBytes -= estimatedMatchingStringCacheBytes(eldest.key, eldest.value)
            entries.remove()
        }
    }

    @Synchronized
    internal fun matchingStringCacheSize(): Int = matchingStrings.size

    @Suppress("ReturnCount")
    private fun matchingContainsStringIds(
        expected: String,
        workConsumer: GraphWorkConsumer?
    ): IntArray {
        val index = getTrigramIndex(workConsumer)
            ?: return scanMatchingStringIds(null, StringMatchMode.CONTAINS, expected, workConsumer)
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
            workConsumer?.consume()
            if (stringTable.get(stringId).contains(expected)) matched[size++] = stringId
        }
        return matched.copyOf(size).also(java.util.Arrays::sort)
    }

    private fun scanMatchingStringIds(
        transform: StringValueTransform?,
        mode: StringMatchMode,
        expected: String,
        workConsumer: GraphWorkConsumer?
    ): IntArray {
        val matched = IntArray(uniqueStringIds.size)
        var size = 0
        for (stringId in uniqueStringIds) {
            workConsumer?.consume()
            val actual = transformString(stringTable.get(stringId), transform)
            val matches = when (mode) {
                StringMatchMode.STARTS_WITH -> actual.startsWith(expected)
                StringMatchMode.ENDS_WITH -> actual.endsWith(expected)
                StringMatchMode.CONTAINS -> actual.contains(expected)
            }
            if (matches) matched[size++] = stringId
        }
        return matched.copyOf(size)
    }

    private fun getTrigramIndex(workConsumer: GraphWorkConsumer?): Int2ObjectOpenHashMap<IntArray>? {
        if (!trigramIndexInitialized) {
            trigramIndex = buildTrigramIndex(workConsumer)
            trigramIndexInitialized = true
        }
        return trigramIndex
    }

    private fun buildTrigramIndex(workConsumer: GraphWorkConsumer?): Int2ObjectOpenHashMap<IntArray>? {
        if (uniqueStringIds.size > MAX_TRIGRAM_INDEX_STRINGS) return null
        val builder = TrigramIndexBuilder(maxTrigramPostings, maxTrigramBytes)
        return if (populateTrigramIndex(builder, workConsumer)) builder.build() else null
    }

    private fun populateTrigramIndex(
        builder: TrigramIndexBuilder,
        workConsumer: GraphWorkConsumer?
    ): Boolean {
        for (stringId in uniqueStringIds) {
            workConsumer?.consume()
            if (!builder.add(stringId, stringTable.get(stringId))) return false
        }
        return true
    }
}

private class TrigramIndexBuilder(
    private val maxPostings: Int,
    private val maxBytes: Long
) {
    private val builders = Int2ObjectOpenHashMap<IntArrayList>()
    private val seenTrigrams = IntOpenHashSet()
    private var postings = 0
    private var estimatedBytes = 0L

    fun add(stringId: Int, value: String): Boolean {
        seenTrigrams.clear()
        for (position in 0..value.length - MIN_TRIGRAM_LENGTH) {
            val trigram = trigramHash(value, position)
            if (!seenTrigrams.add(trigram)) continue
            val posting = builders[trigram]
            val addedBytes = TRIGRAM_POSTING_ESTIMATED_BYTES +
                if (posting == null) TRIGRAM_ENTRY_ESTIMATED_BYTES else 0L
            if (postings >= maxPostings || estimatedBytes + addedBytes > maxBytes) return false
            val target = posting ?: IntArrayList().also { builders.put(trigram, it) }
            target.add(stringId)
            postings++
            estimatedBytes += addedBytes
        }
        return true
    }

    fun build(): Int2ObjectOpenHashMap<IntArray> {
        val result = Int2ObjectOpenHashMap<IntArray>(builders.size)
        builders.int2ObjectEntrySet().forEach { entry ->
            result.put(entry.intKey, entry.value.toIntArray())
        }
        return result
    }
}

private fun estimatedMatchingStringCacheBytes(key: StringMatchKey, strings: IntArray): Long =
    STRING_MATCH_CACHE_ENTRY_ESTIMATED_BYTES + PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES +
        STRING_HEADER_ESTIMATED_BYTES + key.expected.length.toLong() * Char.SIZE_BYTES +
        strings.size.toLong() * Int.SIZE_BYTES

private fun estimatedStringPropertyAdmissionBytes(key: StringPropertyAdmissionKey): Long =
    STRING_PROPERTY_ADMISSION_ESTIMATED_BYTES +
        key.property.property.length.toLong() * Char.SIZE_BYTES +
        key.expected.length.toLong() * Char.SIZE_BYTES

private fun stringMatches(
    actual: String,
    transform: StringValueTransform?,
    mode: StringMatchMode,
    expected: String
): Boolean {
    val transformed = transformString(actual, transform)
    return when (mode) {
        StringMatchMode.STARTS_WITH -> transformed.startsWith(expected)
        StringMatchMode.ENDS_WITH -> transformed.endsWith(expected)
        StringMatchMode.CONTAINS -> transformed.contains(expected)
    }
}

private fun transformString(value: String, transform: StringValueTransform?): String = when (transform) {
    null -> value
    StringValueTransform.LOWERCASE -> value.lowercase()
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
