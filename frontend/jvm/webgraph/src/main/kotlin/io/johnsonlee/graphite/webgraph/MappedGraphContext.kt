package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.graph.DeclaredTypeBinding
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.input.ResourceAccessor
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One owner for every lazy sidecar, cache, lock and scan counter of a loaded graph. */
internal class MappedGraphContext(
    nodes: MappedNodeStorage,
    edges: MappedEdgeStorage,
    sidecars: MappedGraphSidecars,
    indexes: MappedIndexFiles
) {
    internal val mappedNodeData = nodes.mappedNodeData
    internal val nodeDataVersion = nodes.nodeDataVersion
    internal val stringTable = nodes.stringTable
    internal val nodeOffsets = nodes.nodeOffsets
    internal val nodeTypeIndex = nodes.nodeTypeIndex
    internal val forward = edges.forward
    internal val backward = edges.backward
    internal val forwardLabels = edges.forwardLabels
    internal val cumulativeOutdeg = edges.cumulativeOutdeg
    internal val edgeCount = edges.edgeCount
    internal val comparisonLookup = edges.comparisonLookup
    internal val metadataFile = sidecars.metadataFile
    internal val metadata = sidecars.metadata
    internal val methodCount = sidecars.methodCount
    internal val classOverviewProvider = sidecars.classOverviewProvider
    internal val resourceAccessor = sidecars.resourceAccessor
    internal val branchDefinitions = sidecars.branchDefinitions
    internal val callSiteOrdinals = sidecars.callSiteOrdinals
    internal val declaredTypeTable = sidecars.declarations.declaredTypeTable
    internal val declaredPresence = sidecars.declarations.declaredPresence
    internal val analysisCompleteness = sidecars.declarations.analysisCompleteness
    internal val callSiteStringIndexFile = indexes.callSiteStringIndexFile
    internal val persistentCallSiteStringIndexEnabled = indexes.persistentCallSiteStringIndexEnabled

    internal val resources: ResourceAccessor
        get() = resourceAccessor.value

    internal val branchScopeIndex: Map<Int, List<BranchScope>> by lazy {
        val sidecar = branchDefinitions.value.scopes
        metadata.value.branchScopes
            .mapIndexed { index, raw -> raw.toBranchScope(sidecar.getOrNull(index)) }
            .groupBy { it.conditionNodeId.value }
    }

    internal val localDefinitionIndex: Map<NodeId, List<LocalDefinition>> by lazy {
        BranchScope.unpackDefinitionTable(branchDefinitions.value.locals)
    }

    internal val recordBinding: DeclaredTypeBinding? by lazy {
        (declaredTypeTable.value.types as? MappedDeclaredTypeIds)?.binding()?.let(::RecordDeclaredTypeBinding)
    }

    /**
     * Reads the string IDs straight off a node's record (after the 4-byte id and the tag) and off the method index's arrays, so binding a
     * node costs a few integer reads and one probe.
     */
    internal inner class RecordDeclaredTypeBinding(private val ids: MappedDeclaredTypeBinding) : DeclaredTypeBinding {
        override fun fieldTypeId(node: FieldNode): Int {
            val record = record(node.id.value, NodeSerializer.TAG_FIELD_NODE)
            if (record < 0) return DeclaredTypeBinding.UNANSWERED
            return ids.fieldTypeId(
                mappedNodeData.getInt(record),
                mappedNodeData.getInt(record + Int.SIZE_BYTES),
                mappedNodeData.getInt(record + 2 * Int.SIZE_BYTES))
        }

        override fun memberTypeId(node: Node): Int {
            val (methodAt, parameterIndex) =
                when (node) {
                    // index, type, then the method
                    is ParameterNode ->
                        record(node.id.value, NodeSerializer.TAG_PARAMETER_NODE).let { if (it < 0) it else it + 2 * Int.SIZE_BYTES } to
                            node.index
                    is ReturnNode -> record(node.id.value, NodeSerializer.TAG_RETURN_NODE) to -1
                    else -> -1 to -1
                }
            if (methodAt < 0) return DeclaredTypeBinding.UNANSWERED
            return readMemberType(methodAt, parameterIndex)
        }

        private fun readMemberType(methodAt: Int, parameterIndex: Int): Int {
            val owner = mappedNodeData.getInt(methodAt)
            val name = mappedNodeData.getInt(methodAt + Int.SIZE_BYTES)
            val count = mappedNodeData.getInt(methodAt + 2 * Int.SIZE_BYTES)
            val end = methodAt + (METHOD_DESCRIPTOR_FIXED_INTS + count) * Int.SIZE_BYTES
            if (count < 0 || end > mappedNodeData.limit()) {
                return DeclaredTypeBinding.UNANSWERED
            }
            val parameters = IntArray(count) { mappedNodeData.getInt(methodAt + (METHOD_PARAMETERS_OFFSET_INTS + it) * Int.SIZE_BYTES) }
            val returns = mappedNodeData.getInt(methodAt + (METHOD_PARAMETERS_OFFSET_INTS + count) * Int.SIZE_BYTES)
            return ids.memberTypeId(owner, name, parameters, returns, parameterIndex)
        }

        override fun methodTypes(method: MethodDescriptor): MethodTypes? {
            val handle = method.declarationHandle
            if (handle < 0) return DeclaredTypeBinding.UNANSWERED_METHOD
            val index = methodIndex()
            return if (index.holds(handle))
                ids.methodTypes(
                    index.declaringClassId(handle), index.nameId(handle), index.parameterTypeIds(handle), index.returnTypeId(handle))
            else DeclaredTypeBinding.UNANSWERED_METHOD
        }

        override fun methodShape(method: MethodDescriptor): Int {
            val handle = method.declarationHandle
            if (handle < 0) return DeclaredTypeBinding.UNANSWERED
            val persisted = declaredPresence.value?.methodShape(handle) ?: DeclaredTypeBinding.UNANSWERED
            return persisted.takeUnless { it == DeclaredTypeBinding.UNANSWERED } ?: indexedMethodShape(handle)
        }

        private fun indexedMethodShape(handle: Int): Int {
            val index = methodIndex()
            if (!index.holds(handle)) return DeclaredTypeBinding.UNANSWERED
            return ids.methodShape(
                index.declaringClassId(handle), index.nameId(handle), index.parameterTypeIds(handle), index.returnTypeId(handle))
        }

        override fun nodePresence(node: Node): Int {
            val presence = declaredPresence.value ?: return DeclaredTypeBinding.UNANSWERED
            val tag =
                when (node) {
                    is FieldNode -> NodeSerializer.TAG_FIELD_NODE
                    is ParameterNode -> NodeSerializer.TAG_PARAMETER_NODE
                    is ReturnNode -> NodeSerializer.TAG_RETURN_NODE
                    else -> null
                }
            return if (tag == null || record(node.id.value, tag) < 0) DeclaredTypeBinding.UNANSWERED else presence.node(node.id.value)
        }

        /** The byte position of [nodeId]'s tag-specific fields when the record carries [tag], else -1. */
        private fun record(nodeId: Int, tag: Int): Int {
            val position = recordOffset(nodeId) ?: return -1
            return if (mappedNodeData.get(position + Int.SIZE_BYTES).toInt() == tag) position + NODE_HEADER_BYTES else -1
        }

        private fun recordOffset(nodeId: Int): Int? {
            if (nodeId < 0 || nodeId >= nodeOffsets.size) return null
            return nodeOffsets.offset(nodeId).takeIf { it >= 0 && it <= mappedNodeData.limit() - NODE_HEADER_BYTES }?.toInt()
        }
    }

    /** Method records begin at the metadata header; mmap avoids retaining an open stream on lazy early exit. */
    internal val mappedMethodMetadata: MappedByteBuffer by lazy {
        FileChannel.open(metadataFile.toPath(), StandardOpenOption.READ).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    @Volatile internal var methodIndex: MappedMethodIndex? = null

    internal val methodIndexLock = Any()

    internal val stringPropertyIndexLock = Any()

    internal val stringPropertyAdmissions = StringPropertyAdmissions()

    internal val rawStringMatchStates = RawStringMatchStates()

    internal val rawProjectionMatches = RawProjectionMatches()

    internal val callSiteStringIndexLock = Any()

    internal val callSiteStringIndexIdentityFile = callSiteStringIndexFile.resolveSibling(CALL_SITE_STRING_CONTENT_IDENTITY_FILE)

    @Volatile internal var callSiteStringIndex: MappedCallSiteStringIndex? = null

    internal val mappedCallSiteStringIndexViewLock = Any()

    @Volatile internal var mappedCallSiteStringIndexView: MappedCallSiteStringIndexView? = null

    @Volatile internal var mappedCallSiteStringIndexViewUnavailable = false

    internal var callSiteStringIndexLoadedFromPersistence = false

    internal val retainPersistedCallSiteStringIndex = AtomicBoolean()

    internal var callSiteStringIndexPersistenceBudgetDenied = false

    internal val callSiteParallelScanCount = AtomicLong()

    internal val callSiteStringLookupEntryCount = AtomicLong()

    internal val callSiteStringIndexLookupCount = AtomicLong()

    internal val callSiteStringPreflightCount = AtomicLong()

    internal val callSiteStringProjectionLookupCount = AtomicLong()

    internal val callSiteScanActiveWorkers = AtomicInteger()

    internal val callSiteScanPeakActiveWorkers = AtomicInteger()

    internal val callSiteScanAbortedWorkers = AtomicLong()

    internal val stringPropertyIndexes =
        object :
            LinkedHashMap<StringPropertyKey, MappedStringPropertyIndex>(
                MAX_STRING_PROPERTY_INDEXES + 1, STRING_PROPERTY_INDEX_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StringPropertyKey, MappedStringPropertyIndex>?): Boolean {
                val remove = size > MAX_STRING_PROPERTY_INDEXES
                if (remove && eldest != null) stringPropertyAdmissions.clear(eldest.key)
                return remove
            }
        }

    internal val includesDeclaredTypeProperties: Boolean = true

    // Publish only completed immutable structural decisions. Cold callers may validate concurrently;
    // none waits on a cancelled initializer, and no request's work consumer is retained by the graph.
    @Volatile internal var callSiteProjectionSupported: Boolean? = null

    val graphCapabilities = MappedGraphCore(this)
    val nodeCapabilities = MappedNodeCapabilities(this)
    val methodCapabilities = MappedMethodCapabilities(this)
    val lookupCapabilities = MappedStringLookupCapabilities(this)
    val projectionCapabilities = MappedProjectionCapabilities(this)
    val cacheCapabilities = MappedCacheCapabilities(this)
}
