@file:Suppress("ComplexCondition", "NestedBlockDepth", "ReturnCount", "StringLiteralDuplication")

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
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.ReleasableStringPropertyDisjunctionCache
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionDistinctProjection
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionProjection
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.StreamingMethodLookup
import io.johnsonlee.graphite.graph.WorkAwareTransformedStringPropertyLookup
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionAggregation
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyLookup
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.lang.MutableString
import it.unimi.dsi.webgraph.ImmutableGraph
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

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
@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
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
    private val callSiteStringIndexFile: Path,
    private val persistentCallSiteStringIndexEnabled: Boolean,
    private val methodCount: Long,
    private val comparisonLookup: BranchComparisonLookup,
    private val metadata: Lazy<GraphMetadata>,
    private val classOverviewProvider: (Int) -> ClassOverview?,
    private val resourceAccessor: Lazy<ResourceAccessor>
) : Graph,
    StreamingMethodLookup,
    WorkAwareStringPropertyLookup,
    WorkAwareTransformedStringPropertyLookup,
    WorkAwareStringPropertyDisjunctionLookup,
    WorkAwareStringPropertyDisjunctionAggregation,
    StringPropertyDisjunctionProjection,
    StringPropertyDisjunctionDistinctProjection,
    ReleasableStringPropertyDisjunctionCache,
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

    /** Method records begin at the metadata header; mmap avoids retaining an open stream on lazy early exit. */
    private val mappedMethodMetadata: MappedByteBuffer by lazy {
        FileChannel.open(metadataFile.toPath(), StandardOpenOption.READ).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }
    @Volatile
    private var methodIndex: MappedMethodIndex? = null
    private val methodIndexLock = Any()

    private val stringPropertyIndexLock = Any()
    private val stringPropertyAdmissions = StringPropertyAdmissions()
    private val rawStringMatchStates = RawStringMatchStates()
    private val rawProjectionMatches = RawProjectionCache<RawProjectionMatchKey, IntArray>()
    private val rawProjectionRows =
        RawProjectionCache<RawProjectionRowsKey, List<StringPropertyProjectionRow>>(MAX_RAW_PROJECTION_ROW_CACHE_BYTES)
    private val callSiteStringIndexLock = Any()
    private val callSiteStringIndexIdentityFile =
        callSiteStringIndexFile.resolveSibling(CALL_SITE_STRING_CONTENT_IDENTITY_FILE)
    @Volatile
    private var callSiteStringIndex: MappedCallSiteStringIndex? = null
    private val mappedCallSiteStringIndexViewLock = Any()
    @Volatile
    private var mappedCallSiteStringIndexView: MappedCallSiteStringIndexView? = null
    @Volatile
    private var mappedCallSiteStringIndexViewUnavailable = false
    private var callSiteStringIndexLoadedFromPersistence = false
    private var callSiteStringIndexPersistenceBudgetDenied = false
    private val rawCallSiteStringIds = CallSiteRawStringIds { nodeId, target ->
        withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
            target[CALLER_CLASS_PROPERTY_INDEX] = callerClass
            target[CALLER_NAME_PROPERTY_INDEX] = callerName
            target[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
            target[CALLEE_NAME_PROPERTY_INDEX] = calleeName
        }
    }
    private fun callSiteStringIndexContentIdentity(workConsumer: GraphWorkConsumer?): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(stringTable.contentIdentity(workConsumer))
        digest.updateIdentityInt(nodeTypeIndex.count(CallSiteNode::class.java).toInt())
        forEachRawCallSiteStringIds(workConsumer) {
                nodeId, callerClass, callerName, calleeClass, calleeName ->
            digest.updateIdentityInt(nodeId)
            digest.updateIdentityLong(nodeOffsets.offset(nodeId))
            digest.updateIdentityInt(callerClass)
            digest.updateIdentityInt(callerName)
            digest.updateIdentityInt(calleeClass)
            digest.updateIdentityInt(calleeName)
        }
        return digest.digest()
    }
    private val callSiteStringLookupEntryCount = AtomicLong()
    private val callSiteStringIndexLookupCount = AtomicLong()
    private val callSiteMappedViewLookupCount = AtomicLong()
    private val callSiteStringPreflightCount = AtomicLong()
    private val callSiteStringProjectionLookupCount = AtomicLong()
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

    override fun stringPropertyNodeOrder(node: Node): Long = nodeOffsets.offset(node.id.value)

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

    override fun <T : Node> nodesByStringPropertyDisjunction(
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int
    ): Sequence<T>? = lookupStringPropertyDisjunction(type, predicates, limit, workConsumer = null)

    override fun <T : Node> nodesByStringPropertyDisjunction(
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): Sequence<T>? = lookupStringPropertyDisjunction(type, predicates, limit, workConsumer)

    override fun aggregateStringPropertyDisjunction(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>,
        distinctProperty: String?
    ): StringPropertyDisjunctionAggregate? = aggregateStringPropertyDisjunctionInternal(
        type,
        predicates,
        distinctProperty,
        workConsumer = null
    )

    override fun aggregateStringPropertyDisjunction(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>,
        distinctProperty: String?,
        workConsumer: GraphWorkConsumer
    ): StringPropertyDisjunctionAggregate? = aggregateStringPropertyDisjunctionInternal(
        type,
        predicates,
        distinctProperty,
        workConsumer as GraphWorkConsumer?
    )

    private fun aggregateStringPropertyDisjunctionInternal(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>,
        distinctProperty: String?,
        workConsumer: GraphWorkConsumer?
    ): StringPropertyDisjunctionAggregate? {
        if (type != CallSiteNode::class.java || predicates.isEmpty() ||
            predicates.any { !supportsRawStringProperty(type, it.property) } ||
            distinctProperty?.let { !supportsRawStringProperty(type, it) } == true
        ) {
            return null
        }
        callSiteStringLookupEntryCount.incrementAndGet()
        callSiteStringIndex?.let { index ->
            callSiteStringIndexLookupCount.incrementAndGet()
            return index.aggregate(predicates, distinctProperty, workConsumer)
        }
        if (distinctProperty == null) {
            mappedCallSiteStringIndexView(workConsumer)?.let { view ->
                val plan = view.matchPlan(predicates, workConsumer)
                if (plan != null) {
                    callSiteMappedViewLookupCount.incrementAndGet()
                    return StringPropertyDisjunctionAggregate(
                        view.countMatchingNodes(plan, nodeOffsets.size, workConsumer)
                    )
                }
            }
        }
        if (shouldPreflightCallSitePredicates(predicates) &&
            callSitePredicatesCannotMatch(predicates, workConsumer)
        ) {
            return StringPropertyDisjunctionAggregate(
                count = 0,
                distinctValues = if (distinctProperty == null) null else emptySet()
            )
        }
        val index = callSiteStringIndex(type, workConsumer) ?: return null
        callSiteStringIndexLookupCount.incrementAndGet()
        return index.aggregate(predicates, distinctProperty, workConsumer)
    }

    /**
     * Bounded DISTINCT projection on the requesting thread. A retained heap index serves first;
     * otherwise the validated mapped sidecar view answers directly, using a bounded raw prefix
     * probe when the matched postings are dense. Legacy graphs without a sidecar build the
     * retained index once and let the release path persist it for the next process.
     */
    @Suppress("CyclomaticComplexMethod")
    override fun distinctStringPropertyDisjunction(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        selectedValues: Set<List<String?>>?,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow>? {
        if (type != CallSiteNode::class.java || predicates.isEmpty() ||
            predicates.any { !supportsRawStringProperty(type, it.property) } ||
            projectedProperties.any { property ->
                property != GRAPH_ID_PROJECTION_PROPERTY && property !in CALL_SITE_NULL_STRING_PROPERTIES &&
                    !supportsRawStringProperty(type, property)
            }
        ) {
            return null
        }
        callSiteStringLookupEntryCount.incrementAndGet()
        if (limit <= 0 || selectedValues?.isEmpty() == true) return emptyList()
        callSiteStringIndex?.let { index ->
            callSiteStringProjectionLookupCount.incrementAndGet()
            return index.distinctProjection(predicates, projectedProperties, limit, selectedValues, workConsumer)
        }
        mappedCallSiteStringIndexView(workConsumer)?.let { view ->
            val projectedPropertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex).toIntArray()
            if (selectedValues != null) {
                callSiteMappedViewLookupCount.incrementAndGet()
                return view.selectedTupleHits(predicates, projectedPropertyIndexes, selectedValues, workConsumer)
                    .sortedBy(StringPropertyDistinctRow::encounterOrder)
                    .take(minOf(limit, selectedValues.size))
            }
            val plan = view.matchPlan(predicates, workConsumer)
            if (plan != null) {
                callSiteMappedViewLookupCount.incrementAndGet()
                if (plan.knownEmpty) return emptyList()
                if (isDensePlan(plan, limit, DISTINCT_RAW_PROBE_MAX_LIMIT)) {
                    rawDistinctCallSiteStringProjection(
                        predicates,
                        projectedProperties,
                        limit,
                        selectedValues = null,
                        workConsumer,
                        maxInspected = rawProbeNodeBudget(limit, DENSE_RAW_PROBE_FACTOR)
                    )?.let { return it }
                }
                view.distinctRows(plan, projectedPropertyIndexes, limit, workConsumer)?.let { return it }
            }
        }
        if (shouldPreflightCallSitePredicates(predicates) && callSitePredicatesCannotMatch(predicates, workConsumer)) {
            return emptyList()
        }
        val index = callSiteStringIndex(type, workConsumer) ?: return checkNotNull(
            rawDistinctCallSiteStringProjection(predicates, projectedProperties, limit, selectedValues, workConsumer)
        )
        callSiteStringProjectionLookupCount.incrementAndGet()
        return index.distinctProjection(predicates, projectedProperties, limit, selectedValues, workConsumer)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "LoopWithTooManyJumpStatements")
    private fun rawDistinctCallSiteStringProjection(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        selectedValues: Set<List<String?>>?,
        workConsumer: GraphWorkConsumer?,
        maxInspected: Int = Int.MAX_VALUE
    ): List<StringPropertyDistinctRow>? {
        if (limit <= 0 || selectedValues?.isEmpty() == true) return emptyList()
        val predicatePropertyIndexes = predicates.map { predicate ->
            requiredCallSiteStringPropertyIndex(predicate.property)
        }
        val projectedPropertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex)
        val sharedMatchers = mutableMapOf<StringPredicateKey, BoundedStringMatcher>()
        val matchers = predicates.map { predicate ->
            val key = StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)
            sharedMatchers.getOrPut(key) { BoundedStringMatcher(stringTable, key) }
        }
        val rows = mutableListOf<StringPropertyDistinctRow>()
        val seenValues = HashSet<List<String?>>()
        val targetSize = minOf(limit, selectedValues?.size ?: limit)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        val nodeIds = nodeTypeIndex.idIterator(CallSiteNode::class.java)
        var inspected = 0
        var checkpoint = rawProbeNodeBudget(targetSize, RAW_PROJECTION_PROBE_FACTOR)
        try {
            while (nodeIds.hasNext()) {
                if (inspected >= maxInspected) return null
                if (inspected == checkpoint && checkpoint < maxInspected) {
                    if (rawProbeProjectsPastBudget(inspected, rows.size, targetSize, maxInspected)) return null
                    checkpoint *= 2
                }
                val nodeId = nodeIds.nextInt()
                if ((inspected++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0 &&
                    Thread.currentThread().isInterrupted
                ) {
                    throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
                }
                accounting.consume()
                var matched = false
                withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                    stringIds[CALLER_CLASS_PROPERTY_INDEX] = callerClass
                    stringIds[CALLER_NAME_PROPERTY_INDEX] = callerName
                    stringIds[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
                    stringIds[CALLEE_NAME_PROPERTY_INDEX] = calleeName
                    var predicateIndex = 0
                    while (!matched && predicateIndex < predicates.size) {
                        matched = matchers[predicateIndex].matches(stringIds[predicatePropertyIndexes[predicateIndex]])
                        predicateIndex++
                    }
                }
                if (!matched) continue
                val values = projectedPropertyIndexes.map { propertyIndex ->
                    if (propertyIndex < 0) null else stringTable.get(stringIds[propertyIndex])
                }
                if ((selectedValues != null && values !in selectedValues) || !seenValues.add(values)) continue
                rows += StringPropertyDistinctRow(nodeOffsets.offset(nodeId), values)
                if (rows.size >= targetSize) break
            }
            return rows
        } finally {
            accounting.flush()
        }
    }

    /**
     * Bounded duplicate-preserving projection on the requesting thread with the same retained,
     * mapped, and legacy-build precedence as [distinctStringPropertyDisjunction].
     */
    @Suppress("CyclomaticComplexMethod")
    override fun projectStringPropertyDisjunction(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyProjectionRow>? {
        if (type != CallSiteNode::class.java || predicates.isEmpty() || limit < 0 ||
            predicates.any { !supportsRawStringProperty(type, it.property) } ||
            projectedProperties.any { !supportsRawStringProperty(type, it) }
        ) {
            return null
        }
        callSiteStringLookupEntryCount.incrementAndGet()
        if (limit == 0) return emptyList()
        callSiteStringIndex?.let { index ->
            callSiteStringIndexLookupCount.incrementAndGet()
            return index.projectRows(predicates, projectedProperties, limit, workConsumer)
        }
        val projectedPropertyIndexes = projectedProperties.map(::requiredCallSiteStringPropertyIndex).toIntArray()
        mappedCallSiteStringIndexView(workConsumer)?.let { view ->
            // Repeated bounded projections of one predicate set answer from the rows they already
            // decoded, the way the retained index serves its projection cache.
            val rowsKey = RawProjectionRowsKey(
                RawProjectionMatchKey(predicates, limit),
                projectedPropertyIndexes.asList()
            )
            rawProjectionRows[rowsKey]?.let { cached ->
                callSiteMappedViewLookupCount.incrementAndGet()
                consumeGraphWork(workConsumer, cached.size.coerceAtLeast(1).toLong())
                return cached
            }
            val plan = view.matchPlan(predicates, workConsumer)
            if (plan != null) {
                callSiteMappedViewLookupCount.incrementAndGet()
                if (plan.knownEmpty) return rememberProjectedRows(rowsKey, emptyList())
                if (isDensePlan(plan, limit, RAW_PROBE_MAX_LIMIT)) {
                    probeRawCallSiteNodeIds(
                        predicates,
                        limit,
                        workConsumer,
                        maxInspected = rawProbeNodeBudget(limit, DENSE_RAW_PROBE_FACTOR)
                    )?.let { nodeIds ->
                        return rememberProjectedRows(rowsKey, projectRawCallSiteRows(nodeIds, projectedPropertyIndexes))
                    }
                }
                if (plan.isEmpty) return rememberProjectedRows(rowsKey, emptyList())
                view.projectRows(plan, projectedPropertyIndexes, limit, workConsumer)?.let { rows ->
                    return rememberProjectedRows(rowsKey, rows)
                }
            }
        }
        if (shouldPreflightCallSitePredicates(predicates) && callSitePredicatesCannotMatch(predicates, workConsumer)) {
            return emptyList()
        }
        val index = callSiteStringIndex(type, workConsumer)
            ?: return probeRawCallSiteNodeIds(predicates, limit, workConsumer)?.let { nodeIds ->
                projectRawCallSiteRows(nodeIds, projectedPropertyIndexes)
            }
        callSiteStringIndexLookupCount.incrementAndGet()
        return index.projectRows(predicates, projectedProperties, limit, workConsumer)
    }

    private fun rememberProjectedRows(
        key: RawProjectionRowsKey,
        rows: List<StringPropertyProjectionRow>
    ): List<StringPropertyProjectionRow> {
        val retained = Collections.unmodifiableList(rows)
        var bytes = RAW_PROJECTION_ROW_CACHE_ENTRY_ESTIMATED_BYTES
        for (row in rows) {
            bytes += RAW_PROJECTION_ROW_ESTIMATED_BYTES
            for (value in row.values) {
                bytes += RAW_PROJECTION_STRING_ESTIMATED_BYTES + (value?.length ?: 0) * Char.SIZE_BYTES
            }
        }
        rawProjectionRows.put(key, retained, bytes)
        return retained
    }

    /**
     * Scans a bounded prefix of the CallSite storage order for the first [limit] matches. Returns
     * null when the prefix does not fill [limit], so dense terms answer from a few hundred raw
     * nodes while sparse terms continue on the index. Filled prefixes are cached per graph.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun probeRawCallSiteNodeIds(
        predicates: List<StringPropertyPredicate>,
        limit: Int,
        workConsumer: GraphWorkConsumer?,
        maxInspected: Int = rawProbeNodeBudget(limit, RAW_PROJECTION_PROBE_FACTOR)
    ): IntArray? {
        if (limit <= 0) return IntArray(0)
        val cacheKey = RawProjectionMatchKey(predicates.toList(), limit)
        rawProjectionMatches[cacheKey]?.let { cachedNodeIds ->
            // A prefix that once ran past its budget without filling LIMIT is not rescanned.
            if (cachedNodeIds === RAW_PROBE_EXHAUSTED) return null
            consumeGraphWork(workConsumer, cachedNodeIds.size.coerceAtLeast(1).toLong())
            return cachedNodeIds
        }
        val predicatePropertyIndexes = predicates.map { predicate ->
            requiredCallSiteStringPropertyIndex(predicate.property)
        }
        val sharedStates = mutableMapOf<StringPredicateKey, BoundedStringMatcher>()
        val matchStates = predicates.map { predicate ->
            sharedStates.getOrPut(StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)) {
                BoundedStringMatcher(
                    stringTable,
                    StringPredicateKey(predicate.transform, predicate.mode, predicate.expected),
                    RAW_PROJECTION_STRING_MATCH_CACHE_CAPACITY
                )
            }
        }
        val matchedNodeIds = IntArray(limit)
        var matchedCount = 0
        val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        val nodeIds = nodeTypeIndex.idIterator(CallSiteNode::class.java)
        var inspected = 0
        var checkpoint = rawProbeNodeBudget(limit, RAW_PROJECTION_PROBE_FACTOR)
        try {
            while (nodeIds.hasNext() && inspected < maxInspected) {
                if (inspected == checkpoint) {
                    if (rawProbeProjectsPastBudget(inspected, matchedCount, limit, maxInspected)) break
                    checkpoint *= 2
                }
                val nodeId = nodeIds.nextInt()
                if ((inspected and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0 &&
                    Thread.currentThread().isInterrupted
                ) {
                    throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
                }
                inspected++
                accounting.consume()
                var matched = false
                withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                    stringIds[CALLER_CLASS_PROPERTY_INDEX] = callerClass
                    stringIds[CALLER_NAME_PROPERTY_INDEX] = callerName
                    stringIds[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
                    stringIds[CALLEE_NAME_PROPERTY_INDEX] = calleeName
                    var predicateIndex = 0
                    while (!matched && predicateIndex < predicates.size) {
                        val stringId = stringIds[predicatePropertyIndexes[predicateIndex]]
                        matched = matchStates[predicateIndex].matches(stringId)
                        predicateIndex++
                    }
                }
                if (!matched) continue
                matchedNodeIds[matchedCount++] = nodeId
                if (matchedCount >= limit) break
            }
            if (matchedCount < limit && nodeIds.hasNext()) {
                if (maxInspected >= rawProbeNodeBudget(limit, DENSE_RAW_PROBE_FACTOR)) {
                    rawProjectionMatches.put(cacheKey, RAW_PROBE_EXHAUSTED)
                }
                return null
            }
            val result = matchedNodeIds.copyOf(matchedCount)
            rawProjectionMatches.put(cacheKey, result)
            return result
        } finally {
            accounting.flush()
        }
    }

    private fun projectRawCallSiteRows(
        nodeIds: IntArray,
        projectedPropertyIndexes: IntArray
    ): List<StringPropertyProjectionRow> {
        val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        return nodeIds.map { nodeId ->
            withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                stringIds[CALLER_CLASS_PROPERTY_INDEX] = callerClass
                stringIds[CALLER_NAME_PROPERTY_INDEX] = callerName
                stringIds[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
                stringIds[CALLEE_NAME_PROPERTY_INDEX] = calleeName
            }
            StringPropertyProjectionRow(projectedPropertyIndexes.map { propertyIndex ->
                stringTable.get(stringIds[propertyIndex])
            })
        }
    }

    /**
     * A plan is answered from a bounded raw prefix first when its rarest probed trigram is still
     * common, or when its resolved postings hold many times more nodes than the limit: merging
     * such postings would validate every selected node although only the first [limit] are needed.
     */
    private fun isDensePlan(plan: MappedCallSiteStringIndexView.MatchPlan, limit: Int, maxLimit: Int): Boolean =
        limit in 1..maxLimit &&
            (plan.likelyDense || plan.postingCount >= limit.toLong() * RAW_PROBE_DENSITY_FACTOR)

    private fun rawProbeNodeBudget(limit: Int, factor: Int): Int =
        (limit.toLong() * factor).coerceIn(RAW_PROJECTION_MIN_PROBE_NODES.toLong(), RAW_PROJECTION_MAX_PROBE_NODES.toLong()).toInt()

    /**
     * A probe checkpoint stops early when the prefix matched nothing yet or its running match rate
     * projects the [limit]-th match beyond [maxInspected]: matches of a term that is dense in the
     * postings can still start thousands of nodes into the storage order, and such a prefix costs
     * more to finish than the mapped postings it was meant to avoid.
     */
    private fun rawProbeProjectsPastBudget(inspected: Int, matched: Int, limit: Int, maxInspected: Int): Boolean =
        matched == 0 || inspected.toLong() * limit / matched > maxInspected

    @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun <T : Node> lookupStringPropertyDisjunction(
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): Sequence<T>? {
        if (predicates.isEmpty() || predicates.any { !supportsRawStringProperty(type, it.property) }) return null
        if (limit <= 0) return emptySequence()
        if (type == CallSiteNode::class.java) {
            callSiteStringLookupEntryCount.incrementAndGet()
            callSiteStringIndex?.let { index ->
                callSiteStringIndexLookupCount.incrementAndGet()
                return index.matchingNodeIds(predicates, workConsumer, limit)
                    .mapNotNull { nodeId -> node(NodeId(nodeId)) as? CallSiteNode }
                    .map(type::cast)
            }
            mappedCallSiteStringIndexView(workConsumer)?.let { view ->
                val plan = view.matchPlan(predicates, workConsumer)
                if (plan != null) {
                    callSiteMappedViewLookupCount.incrementAndGet()
                    if (plan.knownEmpty) return emptySequence()
                    if (isDensePlan(plan, limit, RAW_PROBE_MAX_LIMIT)) {
                        probeRawCallSiteNodeIds(
                            predicates,
                            limit,
                            workConsumer,
                            maxInspected = rawProbeNodeBudget(limit, DENSE_RAW_PROBE_FACTOR)
                        )?.let { nodeIds ->
                            return nodeIds.asSequence().mapNotNull { nodeId -> node(NodeId(nodeId)) as? T }
                        }
                    }
                    if (plan.isEmpty) return emptySequence()
                    view.orderedMatchingNodeIds(plan, limit, workConsumer)?.let { nodeIds ->
                        return nodeIds.asSequence().mapNotNull { nodeId -> node(NodeId(nodeId)) as? T }
                    }
                }
            }
            if (shouldPreflightCallSitePredicates(predicates) && callSitePredicatesCannotMatch(predicates, workConsumer)) {
                return emptySequence()
            }
            callSiteStringIndex(type, workConsumer)?.let { index ->
                callSiteStringIndexLookupCount.incrementAndGet()
                return index.matchingNodeIds(predicates, workConsumer, limit)
                    .mapNotNull { nodeId -> node(NodeId(nodeId)) as? CallSiteNode }
                    .map(type::cast)
            }
            serialRawCallSiteStringDisjunction<T>(type, predicates, limit, workConsumer)?.let { return it }
        }
        return sequence {
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            val sharedStates = mutableMapOf<StringPredicateKey, ByteArray>()
            val matchStates = predicates.map { predicate ->
                val predicateKey = StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)
                sharedStates.getOrPut(predicateKey) {
                    ByteArray(stringTable.size())
                }
            }
            var yielded = 0
            var inspected = 0
            try {
                for (nodeId in nodeTypeIndex.ids(type)) {
                    if ((inspected++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0 &&
                        Thread.currentThread().isInterrupted
                    ) {
                        throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
                    }
                    accounting.consume()
                    val matches = predicates.indices.any { index ->
                        val predicate = predicates[index]
                        val stringId = rawStringPropertyIndex(nodeId, type, predicate.property) ?: return@any false
                        val states = matchStates[index]
                        when (states[stringId]) {
                            RAW_STRING_MATCH -> true
                            RAW_STRING_MISS -> false
                            else -> stringMatches(
                                stringTable.get(stringId),
                                predicate.transform,
                                predicate.mode,
                                predicate.expected
                            ).also { matched ->
                                states[stringId] = if (matched) RAW_STRING_MATCH else RAW_STRING_MISS
                            }
                        }
                    }
                    if (matches) {
                        val matchedNode = node(NodeId(nodeId)) as? T
                        if (matchedNode != null) {
                            yielded++
                            accounting.flush()
                            yield(matchedNode)
                            if (yielded >= limit) break
                        }
                    }
                }
            } finally {
                accounting.flush()
            }
        }
    }

    @Suppress("UNCHECKED_CAST", "ReturnCount")
    private fun <T : Node> serialRawCallSiteStringDisjunction(
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): Sequence<T>? {
        if (type != CallSiteNode::class.java || limit == Int.MAX_VALUE) return null
        return sequence {
            val propertyIndexes = predicates.map { predicate ->
                requiredCallSiteStringPropertyIndex(predicate.property)
            }
            val sharedMatchers = mutableMapOf<StringPredicateKey, BoundedStringMatcher>()
            val matchers = predicates.map { predicate ->
                val key = StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)
                sharedMatchers.getOrPut(key) { BoundedStringMatcher(stringTable, key) }
            }
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            val nodeIds = nodeTypeIndex.idIterator(type)
            var inspected = 0
            var yielded = 0
            try {
                while (yielded < limit && nodeIds.hasNext()) {
                    val nodeId = nodeIds.nextInt()
                    if ((inspected++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0 &&
                        Thread.currentThread().isInterrupted
                    ) {
                        throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
                    }
                    accounting.consume()
                    var matched = false
                    withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                        var predicateIndex = 0
                        while (!matched && predicateIndex < predicates.size) {
                            val stringId = when (propertyIndexes[predicateIndex]) {
                                CALLER_CLASS_PROPERTY_INDEX -> callerClass
                                CALLER_NAME_PROPERTY_INDEX -> callerName
                                CALLEE_CLASS_PROPERTY_INDEX -> calleeClass
                                else -> calleeName
                            }
                            matched = matchers[predicateIndex].matches(stringId)
                            predicateIndex++
                        }
                    }
                    if (matched) {
                        val matchedNode = node(NodeId(nodeId)) as? T
                        if (matchedNode != null) {
                            yielded++
                            accounting.flush()
                            yield(matchedNode)
                        }
                    }
                }
            } finally {
                accounting.flush()
            }
        }
    }

    private fun callSitePredicatesCannotMatch(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): Boolean {
        callSiteStringPreflightCount.incrementAndGet()
        val predicateKeys = predicates.mapTo(linkedSetOf()) { predicate ->
            StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)
        }
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (predicate in predicateKeys) {
                if (Thread.currentThread().isInterrupted) {
                    throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
                }
                val actual = MutableString()
                for (stringId in 0 until stringTable.size()) {
                    if ((stringId and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0 &&
                        Thread.currentThread().isInterrupted
                    ) {
                        throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
                    }
                    accounting.consume()
                    stringTable.get(stringId, actual)
                    if (reusableContains(actual, predicate.transform, predicate.expected)) return false
                }
            }
            return true
        } finally {
            accounting.flush()
        }
    }

    private fun shouldPreflightCallSitePredicates(predicates: List<StringPropertyPredicate>): Boolean =
        callSiteStringIndex == null &&
            nodeTypeIndex.count(CallSiteNode::class.java) >= MIN_CALL_SITE_STRING_PREFLIGHT_NODES &&
            predicates.all { predicate ->
                predicate.mode == StringMatchMode.CONTAINS &&
                    predicate.expected.length >= MIN_CALL_SITE_STRING_PREFLIGHT_TERM_LENGTH
            }

    /**
     * Opens and validates the persisted sidecar once per graph lifetime on the requesting thread.
     * A missing or invalid sidecar is remembered until a forced release so repeated cold queries do
     * not repeat the filesystem probe; persisting a fresh sidecar clears that memory.
     */
    private fun mappedCallSiteStringIndexView(
        workConsumer: GraphWorkConsumer?
    ): MappedCallSiteStringIndexView? {
        if (!persistentCallSiteStringIndexEnabled) return null
        mappedCallSiteStringIndexView?.let { return it }
        if (mappedCallSiteStringIndexViewUnavailable) return null
        return synchronized(mappedCallSiteStringIndexViewLock) {
            mappedCallSiteStringIndexView?.let { return@synchronized it }
            if (mappedCallSiteStringIndexViewUnavailable) return@synchronized null
            val callSiteCount = nodeTypeIndex.count(CallSiteNode::class.java)
            if (callSiteCount <= 0L || callSiteCount > Int.MAX_VALUE) {
                mappedCallSiteStringIndexViewUnavailable = true
                return@synchronized null
            }
            if (!Files.isRegularFile(callSiteStringIndexFile) && !persistLegacyCallSiteStringIndex(workConsumer)) {
                mappedCallSiteStringIndexViewUnavailable = true
                return@synchronized null
            }
            val loaded = MappedCallSiteStringIndexView.load(
                callSiteStringIndexFile,
                stringTable.size(),
                callSiteCount.toInt(),
                persistedCallSiteStringIndexContentIdentity(workConsumer),
                stringTable,
                nodeOffsets.size,
                nodeOrder = { nodeId -> nodeOffsets.offset(nodeId) },
                rawStringIds = rawCallSiteStringIds,
                workConsumer = workConsumer
            )
            if (loaded == null) {
                mappedCallSiteStringIndexViewUnavailable = true
            } else {
                mappedCallSiteStringIndexView = loaded
            }
            loaded
        }
    }

    /**
     * A legacy graph without a sidecar builds its CallSite index once on the requesting thread,
     * persists it immediately, and releases the heap copy so this and every later request serve
     * from the mapped view. Persisting inside the first request keeps the cold path identical
     * for every cold query afterwards instead of rebuilding the index request after request.
     */
    private fun persistLegacyCallSiteStringIndex(workConsumer: GraphWorkConsumer?): Boolean {
        if (callSiteStringIndexPersistenceBudgetDenied) return false
        synchronized(callSiteStringIndexLock) {
            if (callSiteStringIndexLoadedFromPersistence) return false
            val index = callSiteStringIndex(CallSiteNode::class.java, workConsumer) ?: return false
            if (!index.prepareTrigramPostings(workConsumer)) return false
            val persisted = persistPreparedCallSiteStringIndex()
            if (persisted) {
                index.close()
                callSiteStringIndex = null
                callSiteStringIndexLoadedFromPersistence = false
            }
            return persisted && Files.isRegularFile(callSiteStringIndexFile)
        }
    }

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
        if (type == CallSiteNode::class.java) {
            val predicate = StringPropertyPredicate(property, transform, mode, expected)
            lookupStringPropertyDisjunction<T>(type, listOf(predicate), limit, workConsumer)?.let { return it }
        }
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
    ): Sequence<T> = index.matchingNodeIds(transform, mode, expected, workConsumer, limit)
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
                matchStates = ByteArray(stringTable.size())
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
        streamMethods(pattern)

    override fun methods(
        pattern: MethodPattern,
        scanConsumer: MethodMetadataScanConsumer
    ): Sequence<MethodDescriptor> = streamMethods(pattern, scanConsumer)

    override fun methodCount(): Long = methodCount

    override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> =
        indexedMethodSlice(pattern, limit, scanConsumer = null)

    override fun methodSlice(
        pattern: MethodPattern,
        limit: Int,
        scanConsumer: MethodMetadataScanConsumer
    ): List<MethodDescriptor> =
        indexedMethodSlice(pattern, limit, scanConsumer)

    private fun indexedMethodSlice(
        pattern: MethodPattern,
        limit: Int,
        scanConsumer: MethodMetadataScanConsumer?
    ): List<MethodDescriptor> {
        if (limit <= 0) return emptyList()
        if (MappedMethodIndex.cannotMatch(pattern, stringTable)) return emptyList()
        if (methodIndex == null) {
            MappedMethodIndex.sliceExact(
                mappedMethodMetadata.duplicate(),
                stringTable,
                methodCount,
                pattern,
                limit,
                scanConsumer
            )?.let { return it }
        }
        return methodIndex().slice(pattern, limit, scanConsumer)
    }

    private fun streamMethods(
        pattern: MethodPattern,
        scanConsumer: MethodMetadataScanConsumer? = null
    ): Sequence<MethodDescriptor> = sequence {
        if (!MappedMethodIndex.cannotMatch(pattern, stringTable)) {
            yieldAll(methodIndex().methods(pattern, scanConsumer))
        }
    }

    private fun methodIndex(): MappedMethodIndex {
        methodIndex?.let { return it }
        return synchronized(methodIndexLock) {
            methodIndex
                ?: MappedMethodIndex.build(
                    mappedMethodMetadata.duplicate(),
                    stringTable,
                    methodCount
                ).also { methodIndex = it }
        }
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

    internal fun isMetadataInitialized(): Boolean = metadata.isInitialized()

    internal fun isMethodIndexInitialized(): Boolean = methodIndex != null

    internal fun clearStringPropertyIndexes() {
        closeCallSiteStringIndex(force = true)
        synchronized(stringPropertyIndexLock) {
            stringPropertyIndexes.clear()
            stringPropertyAdmissions.clear()
            rawStringMatchStates.clear()
            rawProjectionMatches.clear()
            rawProjectionRows.clear()
        }
    }

    override fun releaseStringPropertyDisjunctionCache() {
        closeCallSiteStringIndex(force = false)
        checkCallSiteIndexPersistenceInterrupted()
    }

    private fun closeCallSiteStringIndex(force: Boolean) {
        if (force) synchronized(mappedCallSiteStringIndexViewLock) {
            mappedCallSiteStringIndexView?.close()
            mappedCallSiteStringIndexView = null
            mappedCallSiteStringIndexViewUnavailable = false
        }
        synchronized(callSiteStringIndexLock) {
            // A persisted index restored at startup stays retained across requests; only its
            // request-owned result caches are released between queries.
            if (!force && callSiteStringIndexLoadedFromPersistence) {
                callSiteStringIndex?.clearQueryCaches()
                return
            }
            val index = callSiteStringIndex
            if (!force && !Thread.currentThread().isInterrupted && persistentCallSiteStringIndexEnabled &&
                !callSiteStringIndexLoadedFromPersistence && index?.isTrigramPostingsInitialized() == true
            ) {
                // Keep the bounded structural index available for the next request and hand its
                // best-effort persistence to graph close. Request-owned result caches are released
                // now, and an interrupted request still follows the prompt close path below.
                index.clearQueryCaches()
                return
            }
            if (force && persistentCallSiteStringIndexEnabled &&
                !callSiteStringIndexLoadedFromPersistence &&
                index?.isTrigramPostingsInitialized() == true
            ) {
                try {
                    persistPreparedCallSiteStringIndex()
                } finally {
                    index.close()
                    callSiteStringIndex = null
                    callSiteStringIndexLoadedFromPersistence = false
                }
                return
            }
            callSiteStringIndex?.close()
            callSiteStringIndex = null
            callSiteStringIndexLoadedFromPersistence = false
        }
    }

    internal fun rawStringMatchStateBytes(): Long = rawStringMatchStates.retainedBytes()

    internal fun rawStringMatchStateCount(): Int = rawStringMatchStates.size()

    /** Filled raw prefixes remembered for this graph; exhausted-prefix markers are not counted. */
    internal fun rawProjectionMatchCount(): Int = rawProjectionMatches.count { nodeIds -> nodeIds !== RAW_PROBE_EXHAUSTED }

    internal fun callSiteStringIndexBytes(): Long = callSiteStringIndex?.retainedBytes ?: 0L

    internal fun hasExactCallSiteProjectionTupleIndex(): Boolean =
        callSiteStringIndex?.hasExactProjectionTupleIndex() == true

    internal fun isCallSiteStringIndexInitialized(): Boolean = callSiteStringIndex != null

    internal fun isMappedCallSiteStringIndexViewInitialized(): Boolean = mappedCallSiteStringIndexView != null

    internal fun mappedPostingRangeValidationCount(): Int =
        mappedCallSiteStringIndexView?.validatedPostingRangeCount() ?: 0

    internal fun mappedPostingRangeValidationBytes(): Long =
        mappedCallSiteStringIndexView?.validatedPostingRangeBytes() ?: 0L

    internal fun isCallSiteTrigramIndexInitialized(): Boolean =
        callSiteStringIndex?.isTrigramPostingsInitialized() == true

    internal fun isCallSiteStringIndexLoadedFromPersistence(): Boolean =
        callSiteStringIndexLoadedFromPersistence

    /**
     * Prepares the complete CallSite string search path before the graph is exposed to queries.
     * A denied shared-memory reservation is a normal fallback and leaves raw scans available.
     */
    internal fun prepareCallSiteStringIndex(
        workConsumer: GraphWorkConsumer = CALL_SITE_INDEX_PREPARATION_WORK_CONSUMER
    ): Boolean {
        val index = callSiteStringIndex(CallSiteNode::class.java, workConsumer) ?: return false
        return index.prepareTrigramPostings(workConsumer)
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun persistPreparedCallSiteStringIndex(): Boolean = synchronized(callSiteStringIndexLock) {
        val index = callSiteStringIndex ?: return@synchronized false
        if (callSiteStringIndexLoadedFromPersistence && Files.isRegularFile(callSiteStringIndexFile)) return@synchronized true
        var temporary: Path? = null
        try {
            temporary = Files.createTempFile(
                callSiteStringIndexFile.parent,
                callSiteStringIndexFile.fileName.toString(),
                ".tmp"
            )
            DataOutputStream(
                BufferedOutputStream(Files.newOutputStream(temporary), CALL_SITE_INDEX_IO_BUFFER_BYTES)
            ).use(index::writePersistent)
            checkCallSiteIndexPersistenceInterrupted()
            replaceAtomically(temporary, callSiteStringIndexFile)
            temporary = null
            // Volatile write: the view lock is not taken here so persisting from inside a view load
            // (which already holds it) and persisting on release never nest the two locks in
            // opposite orders.
            mappedCallSiteStringIndexViewUnavailable = false
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            temporary?.let { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun replaceAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Every CallSite scan runs on the requesting thread; no parallel scan or segment pool exists.
     * The metric accessors stay as functions because harnesses read them reflectively by name.
     */
    @Suppress("FunctionOnlyReturningConstant")
    internal fun callSiteParallelScanCount(): Long = 0L

    internal fun callSiteStringLookupEntryCount(): Long = callSiteStringLookupEntryCount.get()

    internal fun callSiteStringIndexLookupCount(): Long = callSiteStringIndexLookupCount.get()

    internal fun callSiteMappedViewLookupCount(): Long = callSiteMappedViewLookupCount.get()

    internal fun callSiteStringPreflightCount(): Long = callSiteStringPreflightCount.get()

    internal fun callSiteStringProjectionLookupCount(): Long = callSiteStringProjectionLookupCount.get()

    @Suppress("FunctionOnlyReturningConstant")
    internal fun callSiteScanPeakActiveWorkers(): Int = 0

    @Suppress("FunctionOnlyReturningConstant")
    internal fun callSiteSegmentPeakActiveWorkers(): Int = 0

    internal fun resetCallSiteScanMetrics() {
        callSiteStringLookupEntryCount.set(0L)
        callSiteStringIndexLookupCount.set(0L)
        callSiteMappedViewLookupCount.set(0L)
        callSiteStringPreflightCount.set(0L)
        callSiteStringProjectionLookupCount.set(0L)
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
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (nodeId in nodeTypeIndex.ids(type)) {
                accounting.consume()
                val stringId = rawStringPropertyIndex(nodeId, type, property) ?: continue
                nodeIds[size] = nodeId
                stringIds[size] = stringId
                size++
            }
        } finally {
            accounting.flush()
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

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught")
    private fun callSiteStringIndex(
        type: Class<out Node>,
        workConsumer: GraphWorkConsumer? = null
    ): MappedCallSiteStringIndex? {
        if (type != CallSiteNode::class.java) return null
        callSiteStringIndex?.let { return it }
        return synchronized(callSiteStringIndexLock) {
            callSiteStringIndex?.let { return@synchronized it }
            val nodeCount = nodeTypeIndex.count(CallSiteNode::class.java)
            if (nodeCount <= 0L || nodeCount > Int.MAX_VALUE) return@synchronized null
            if (persistentCallSiteStringIndexEnabled) {
                loadPersistedCallSiteStringIndex(nodeCount.toInt(), workConsumer)?.let { persisted ->
                    callSiteStringIndex = persisted
                    callSiteStringIndexLoadedFromPersistence = true
                    return@synchronized persisted
                }
                if (callSiteStringIndexPersistenceBudgetDenied) return@synchronized null
            }
            val stringCount = stringTable.size()
            val countBytes = estimatedMappedCallSiteStringIndexCountBytes(stringCount) ?: return@synchronized null
            val reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(countBytes)
                ?: return@synchronized null
            try {
                val capacity = nodeCount.toInt()
                val endsByStringId = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(stringCount) }
                forEachRawCallSiteStringIds(workConsumer) {
                        _, callerClass, callerName, calleeClass, calleeName ->
                    endsByStringId[CALLER_CLASS_PROPERTY_INDEX][callerClass]++
                    endsByStringId[CALLER_NAME_PROPERTY_INDEX][callerName]++
                    endsByStringId[CALLEE_CLASS_PROPERTY_INDEX][calleeClass]++
                    endsByStringId[CALLEE_NAME_PROPERTY_INDEX][calleeName]++
                }
                val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                    endsByStringId[propertyIndex].count { count -> count > 0 }
                }
                val retainedBytes = estimatedMappedCallSiteStringIndexRetainedBytes(
                    nodeCount,
                    stringCount,
                    uniqueCounts
                ) ?: run {
                    reservation.close()
                    return@synchronized null
                }
                if (!reservation.tryGrowTo(retainedBytes)) {
                    reservation.close()
                    return@synchronized null
                }
                val usedStringIds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                    IntArray(uniqueCounts[propertyIndex])
                }
                val postingEnds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                    IntArray(uniqueCounts[propertyIndex])
                }
                val postings = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(capacity) }
                endsByStringId.indices.forEach { propertyIndex ->
                    val ends = endsByStringId[propertyIndex]
                    val used = usedStringIds[propertyIndex]
                    var usedIndex = 0
                    var postingOffset = 0
                    for (stringId in ends.indices) {
                        val count = ends[stringId]
                        if (count == 0) continue
                        used[usedIndex++] = stringId
                        ends[stringId] = postingOffset
                        postingOffset += count
                    }
                    check(usedIndex == used.size && postingOffset == capacity)
                }
                forEachRawCallSiteStringIds(workConsumer) {
                        nodeId, callerClass, callerName, calleeClass, calleeName ->
                    postings[CALLER_CLASS_PROPERTY_INDEX][
                        endsByStringId[CALLER_CLASS_PROPERTY_INDEX][callerClass]++
                    ] = nodeId
                    postings[CALLER_NAME_PROPERTY_INDEX][
                        endsByStringId[CALLER_NAME_PROPERTY_INDEX][callerName]++
                    ] = nodeId
                    postings[CALLEE_CLASS_PROPERTY_INDEX][
                        endsByStringId[CALLEE_CLASS_PROPERTY_INDEX][calleeClass]++
                    ] = nodeId
                    postings[CALLEE_NAME_PROPERTY_INDEX][
                        endsByStringId[CALLEE_NAME_PROPERTY_INDEX][calleeName]++
                    ] = nodeId
                }
                postingEnds.indices.forEach { propertyIndex ->
                    val used = usedStringIds[propertyIndex]
                    val ends = endsByStringId[propertyIndex]
                    val compactEnds = postingEnds[propertyIndex]
                    compactEnds.indices.forEach { row -> compactEnds[row] = ends[used[row]] }
                }
                reservation.shrinkTo(retainedBytes)
                MappedCallSiteStringIndex(
                    Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                        MappedCallSiteStringIndex.PropertyCsr(
                            postingEnds[propertyIndex],
                            usedStringIds[propertyIndex],
                            postings[propertyIndex]
                        )
                    },
                    stringTable,
                    nodeOrder = { nodeId -> nodeOffsets.offset(nodeId) },
                    nodeIdCapacity = nodeOffsets.size,
                    rawStringPropertyId = ::rawCallSiteStringPropertyId,
                    contentIdentity = { callSiteStringIndexContentIdentity(workConsumer = null) },
                    reservation = reservation
                ).also { built ->
                    callSiteStringIndex = built
                    callSiteStringIndexLoadedFromPersistence = false
                }
            } catch (error: Throwable) {
                reservation.close()
                throw error
            }
        }
    }

    @Suppress("SwallowedException", "ThrowsCount", "TooGenericExceptionCaught")
    private fun loadPersistedCallSiteStringIndex(
        callSiteCount: Int,
        workConsumer: GraphWorkConsumer?
    ): MappedCallSiteStringIndex? {
        callSiteStringIndexPersistenceBudgetDenied = false
        if (!Files.isRegularFile(callSiteStringIndexFile)) return null
        // Identity validation is part of the first relevant query and must obey the same
        // cancellation/work budget as the index lookup it enables. New stores persist the
        // full build-time identity; legacy stores derive it from mapped graph content once.
        val contentIdentity = persistedCallSiteStringIndexContentIdentity(workConsumer)
        return try {
            DataInputStream(
                BufferedInputStream(Files.newInputStream(callSiteStringIndexFile), CALL_SITE_INDEX_IO_BUFFER_BYTES)
            ).use { input ->
                val loaded = MappedCallSiteStringIndex.readPersistent(
                    input,
                    stringTable.size(),
                    callSiteCount,
                    contentIdentity,
                    nodeOrder = { nodeId -> nodeOffsets.offset(nodeId) },
                    nodeIdCapacity = nodeOffsets.size,
                    rawStringPropertyId = ::rawCallSiteStringPropertyId,
                    stringTable = stringTable,
                    workConsumer = workConsumer
                )
                if (loaded != null) {
                    try {
                        val trailingDataWork = PersistentIndexReadWork(workConsumer)
                        trailingDataWork.consume()
                        require(input.read() == -1) { "Trailing data in persisted CallSite string index" }
                        trailingDataWork.flush()
                    } catch (error: Throwable) {
                        loaded.close()
                        throw error
                    }
                }
                loaded
            }
        } catch (_: MappedCallSiteStringIndexPersistenceBudgetDeniedException) {
            callSiteStringIndexPersistenceBudgetDenied = true
            null
        } catch (aborted: MappedCallSiteStringIndexReadAbortedException) {
            throw checkNotNull(aborted.cause)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun persistedCallSiteStringIndexContentIdentity(workConsumer: GraphWorkConsumer?): ByteArray {
        val persisted = runCatching { Files.readAllBytes(callSiteStringIndexIdentityFile) }
            .getOrNull()
            ?.takeIf { identity -> identity.size == CALL_SITE_STRING_INDEX_IDENTITY_BYTES }
        if (persisted != null) {
            consumeGraphWork(workConsumer, 1L)
            return persisted
        }
        return callSiteStringIndexContentIdentity(workConsumer)
    }

    private inline fun forEachRawCallSiteStringIds(
        workConsumer: GraphWorkConsumer?,
        action: (Int, Int, Int, Int, Int) -> Unit
    ) {
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        var index = 0
        try {
            for (nodeId in nodeTypeIndex.ids(CallSiteNode::class.java)) {
                if ((index++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0 &&
                    Thread.currentThread().isInterrupted
                ) {
                    throw CancellationException("Mapped CallSite string index build interrupted")
                }
                accounting.consume()
                withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                    action(nodeId, callerClass, callerName, calleeClass, calleeName)
                }
            }
        } finally {
            accounting.flush()
        }
    }

    private inline fun withRawCallSiteStringIds(
        nodeId: Int,
        action: (Int, Int, Int, Int) -> Unit
    ) {
        val fields = nodeOffsets.offset(nodeId).toInt() + NODE_HEADER_BYTES
        val callerParameters = mappedNodeData.getInt(fields + 2 * Int.SIZE_BYTES)
        val calleeFields = fields + (METHOD_DESCRIPTOR_FIXED_INTS + callerParameters) * Int.SIZE_BYTES
        action(
            mappedNodeData.getInt(fields),
            mappedNodeData.getInt(fields + Int.SIZE_BYTES),
            mappedNodeData.getInt(calleeFields),
            mappedNodeData.getInt(calleeFields + Int.SIZE_BYTES)
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

    private fun requiredCallSiteStringPropertyIndex(property: String): Int =
        callSiteStringPropertyIndex(property).also { propertyIndex ->
            check(propertyIndex >= 0) { "Unsupported CallSite string property: $property" }
        }

    private fun rawCallSiteStringPropertyId(nodeId: Int, propertyIndex: Int): Int {
        val fields = nodeOffsets.offset(nodeId).toInt() + NODE_HEADER_BYTES
        return when (propertyIndex) {
            CALLER_CLASS_PROPERTY_INDEX -> mappedNodeData.getInt(fields)
            CALLER_NAME_PROPERTY_INDEX -> mappedNodeData.getInt(fields + Int.SIZE_BYTES)
            CALLEE_CLASS_PROPERTY_INDEX, CALLEE_NAME_PROPERTY_INDEX -> {
                val callerParameters = mappedNodeData.getInt(fields + 2 * Int.SIZE_BYTES)
                val calleeFields = fields + (METHOD_DESCRIPTOR_FIXED_INTS + callerParameters) * Int.SIZE_BYTES
                mappedNodeData.getInt(
                    calleeFields + if (propertyIndex == CALLEE_NAME_PROPERTY_INDEX) Int.SIZE_BYTES else 0
                )
            }
            else -> error("Unknown CallSite string property index: $propertyIndex")
        }
    }

    private fun readNodeAt(offset: Long): Node {
        val input = ByteBufferDataInput(mappedNodeData, offset.toInt())
        return NodeSerializer.readNode(input, stringTable, nodeDataVersion)
    }
}

private const val NODE_HEADER_BYTES = Int.SIZE_BYTES + Byte.SIZE_BYTES
private const val METHOD_DESCRIPTOR_FIXED_INTS = 4
private const val GRAPH_ID_PROJECTION_PROPERTY = "graphId"
private val CALL_SITE_NULL_STRING_PROPERTIES = setOf("class", "name")
private const val ASCII_MAX_CODE = 0x7f
private const val MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED = "Mapped string-property scan interrupted"
private val CALL_SITE_INDEX_PREPARATION_WORK_CONSUMER = object : GraphWorkBatchConsumer {
    override fun consume(workUnits: Long) = Unit
}
private const val CALL_SITE_INDEX_IO_BUFFER_BYTES = 1 shl 20
internal const val CALL_SITE_STRING_CONTENT_IDENTITY_FILE = "graph.callsite-string-content.identity"
private const val CALL_SITE_STRING_INDEX_IDENTITY_BYTES = 32
private const val MAX_STRING_PROPERTY_INDEXES = 4
private const val MIN_CALL_SITE_STRING_PREFLIGHT_NODES = 4_096L
private const val MIN_CALL_SITE_STRING_PREFLIGHT_TERM_LENGTH = 16
private const val MAX_STRING_PROPERTY_ADMISSION_NODES = 256
private const val MAX_STRING_PROPERTY_ADMISSIONS = 32
private const val MAX_STRING_PROPERTY_ADMISSION_BYTES = 64L * 1024
private const val STRING_PROPERTY_ADMISSION_ESTIMATED_BYTES = 96L
private const val MAX_STRING_PROPERTY_INDEX_RETAINED_BYTES = 8L * 1024 * 1024
private const val MAX_RAW_STRING_MATCH_STATE_BYTES = 16 * 1024 * 1024
private const val MAX_RAW_STRING_MATCH_STATES = 32
private const val LOCAL_STRING_MATCH_CACHE_CAPACITY = 1 shl 16
private const val LOCAL_STRING_MATCH_CACHE_HASH_SHIFT = 16
private const val RAW_PROJECTION_STRING_MATCH_CACHE_CAPACITY = 1 shl 12
private const val RAW_PROJECTION_MIN_PROBE_NODES = 64
private const val RAW_PROJECTION_MAX_PROBE_NODES = 8_192
private const val RAW_PROJECTION_PROBE_FACTOR = 4
private const val RAW_PROBE_MAX_LIMIT = 1_024
private const val RAW_PROBE_DENSITY_FACTOR = 4
private const val DISTINCT_RAW_PROBE_MAX_LIMIT = 1_024

/**
 * Dense plans inspect up to this many raw nodes per requested row before the mapped postings are
 * validated instead: matches of a common term cluster by class in storage order, so a prefix of
 * a few times LIMIT often holds none of them although the postings prove many thousand.
 */
private const val DENSE_RAW_PROBE_FACTOR = 32
private const val MAX_RAW_PROJECTION_MATCH_ENTRIES = 16
private const val MAX_RAW_PROJECTION_ROW_CACHE_BYTES = 512L * 1024
private const val RAW_PROJECTION_ROW_CACHE_ENTRY_ESTIMATED_BYTES = 128L
private const val RAW_PROJECTION_ROW_ESTIMATED_BYTES = 64L
private const val RAW_PROJECTION_STRING_ESTIMATED_BYTES = 40L

/** Marks a predicate set whose bounded raw prefix did not fill LIMIT, so later probes skip it. */
private val RAW_PROBE_EXHAUSTED = IntArray(0)
private const val RAW_STRING_MATCH_STATE_ENTRY_ESTIMATED_BYTES = 96L
private const val STRING_PROPERTY_INDEX_ARRAYS = 3
internal const val PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES = 16L
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
internal const val RAW_STRING_MISS: Byte = 1
internal const val RAW_STRING_MATCH: Byte = 2
private const val RAW_SCAN_INTERRUPTION_POLL_MASK = 1_023
private const val GRAPH_WORK_ACCOUNTING_BATCH_SIZE = 1_024L

private fun estimatedStringPropertyIndexBytes(nodeCount: Long): Long =
    STRING_PROPERTY_INDEX_ARRAYS * PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES +
        nodeCount * STRING_PROPERTY_INDEX_ARRAYS * Int.SIZE_BYTES

internal data class StringPropertyKey(
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

internal data class RawStringMatchKey(
    val property: StringPropertyKey,
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

private data class StringPredicateKey(
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

private data class RawProjectionMatchKey(
    val predicates: List<StringPropertyPredicate>,
    val limit: Int
)

/** Projected rows of one filled raw probe; the decoded strings are what repeated requests pay for. */
private data class RawProjectionRowsKey(
    val match: RawProjectionMatchKey,
    val projectedPropertyIndexes: List<Int>
)

/** Small access-ordered cache of per-graph raw probe results, bounded by entries and estimated bytes. */
private class RawProjectionCache<K : Any, V : Any>(private val maxBytes: Long = Long.MAX_VALUE) {
    private class Entry<V>(val value: V, val bytes: Long)

    private val entries = LinkedHashMap<K, Entry<V>>(
        MAX_RAW_PROJECTION_MATCH_ENTRIES + 1,
        STRING_PROPERTY_INDEX_LOAD_FACTOR,
        true
    )
    private var usedBytes = 0L

    @Synchronized
    operator fun get(key: K): V? = entries[key]?.value

    @Synchronized
    fun put(key: K, value: V, bytes: Long = 0L) {
        if (entries.containsKey(key) || bytes > maxBytes) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() &&
            (entries.size >= MAX_RAW_PROJECTION_MATCH_ENTRIES || usedBytes > maxBytes - bytes)
        ) {
            usedBytes -= iterator.next().value.bytes
            iterator.remove()
        }
        entries[key] = Entry(value, bytes)
        usedBytes += bytes
    }

    @Synchronized
    fun clear() {
        entries.clear()
        usedBytes = 0L
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun count(predicate: (V) -> Boolean): Int = entries.values.count { entry -> predicate(entry.value) }
}

/**
 * Serial, allocation-bounded predicate state for scans over a large global string table.
 * A cache collision only repeats the deterministic comparison and cannot change its result.
 */
private class BoundedStringMatcher(
    private val stringTable: StringTable,
    private val predicate: StringPredicateKey,
    cacheCapacity: Int = LOCAL_STRING_MATCH_CACHE_CAPACITY
) {
    private val stringCount = stringTable.size()
    private val capacity = cacheCapacity.coerceAtLeast(1).takeHighestOneBit()
    private val dense = if (stringCount <= capacity) ByteArray(stringCount) else null
    private val keys = if (dense == null) IntArray(capacity) else null
    private val values = if (dense == null) ByteArray(capacity) else null
    private val actual = MutableString()

    fun matches(stringId: Int): Boolean {
        val state = state(stringId)
        if (state == RAW_STRING_MATCH) return true
        if (state == RAW_STRING_MISS) return false
        stringTable.get(stringId, actual)
        val matched = if (predicate.mode == StringMatchMode.CONTAINS) {
            reusableContains(actual, predicate.transform, predicate.expected)
        } else {
            stringMatches(actual.toString(), predicate.transform, predicate.mode, predicate.expected)
        }
        put(stringId, if (matched) RAW_STRING_MATCH else RAW_STRING_MISS)
        return matched
    }

    private fun state(stringId: Int): Byte {
        dense?.let { return it[stringId] }
        val slot = cacheSlot(stringId)
        return if (keys!![slot] == stringId + 1) values!![slot] else 0
    }

    private fun put(stringId: Int, state: Byte) {
        dense?.let {
            it[stringId] = state
            return
        }
        val slot = cacheSlot(stringId)
        values!![slot] = state
        keys!![slot] = stringId + 1
    }

    private fun cacheSlot(stringId: Int): Int {
        val spread = stringId xor (stringId ushr LOCAL_STRING_MATCH_CACHE_HASH_SHIFT)
        return spread and (capacity - 1)
    }
}

/** Shares predicate state across iterators and enforces one aggregate retained-memory bound per graph. */
internal class RawStringMatchStates(
    private val maxRetainedBytes: Long = MAX_RAW_STRING_MATCH_STATE_BYTES.toLong(),
    private val maxEntries: Int = MAX_RAW_STRING_MATCH_STATES
) {
    private val states = LinkedHashMap<RawStringMatchKey, ByteArray>(
        maxEntries.coerceAtLeast(1) + 1,
        STRING_PROPERTY_INDEX_LOAD_FACTOR,
        true
    )
    private var bytes = 0L

    @Synchronized
    fun stateFor(key: RawStringMatchKey, stringCount: Int): ByteArray? {
        var state = states[key]
        val requiredBytes = estimatedRawStringMatchStateBytes(key, stringCount)
        if (state == null && maxEntries > 0 && requiredBytes <= maxRetainedBytes) {
            while (states.isNotEmpty() &&
                (states.size >= maxEntries || requiredBytes > maxRetainedBytes - bytes)
            ) {
                val eldest = states.entries.iterator().next()
                bytes -= estimatedRawStringMatchStateBytes(eldest.key, eldest.value.size)
                states.remove(eldest.key)
            }
            state = ByteArray(stringCount)
            states[key] = state
            bytes += requiredBytes
        }
        return state
    }

    @Synchronized
    fun contains(type: Class<out Node>, predicate: StringPropertyPredicate): Boolean = states.keys.any { key ->
        key.property.type == type && key.transform == predicate.transform && key.mode == predicate.mode &&
            key.expected == predicate.expected
    }

    @Synchronized
    fun clear() {
        states.clear()
        bytes = 0L
    }

    @Synchronized
    fun retainedBytes(): Long = bytes

    @Synchronized
    fun size(): Int = states.size
}

private fun estimatedRawStringMatchStateBytes(key: RawStringMatchKey, stringCount: Int): Long =
    RAW_STRING_MATCH_STATE_ENTRY_ESTIMATED_BYTES + PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES +
        STRING_HEADER_ESTIMATED_BYTES + key.property.property.length.toLong() * Char.SIZE_BYTES +
        STRING_HEADER_ESTIMATED_BYTES + key.expected.length.toLong() * Char.SIZE_BYTES +
        stringCount.toLong()

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
    ): Sequence<Int> = matchingNodeIds(null, mode, expected, workConsumer = null, limit = Int.MAX_VALUE)

    fun matchingNodeIds(
        transform: StringValueTransform?,
        mode: StringMatchMode,
        expected: String,
        workConsumer: GraphWorkConsumer?,
        limit: Int = Int.MAX_VALUE
    ): Sequence<Int> {
        if (limit <= 0) return emptySequence()
        val matchedStrings = matchingStringIds(transform, mode, expected, workConsumer)
        if (matchedStrings.isEmpty()) return emptySequence()
        return sequence {
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            var yielded = 0
            try {
                for (index in nodeIds.indices) {
                    accounting.consume()
                    if (java.util.Arrays.binarySearch(matchedStrings, stringIds[index]) >= 0) {
                        yielded++
                        accounting.flush()
                        yield(nodeIds[index])
                        if (yielded >= limit) break
                    }
                }
            } finally {
                accounting.flush()
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
            transform == null && mode == StringMatchMode.EQUALS ->
                stringTable.findId(expected).takeIf { it >= 0 }?.let { intArrayOf(it) } ?: IntArray(0)
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
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (stringId in shortestPosting) {
                accounting.consume()
                if (stringTable.get(stringId).contains(expected)) matched[size++] = stringId
            }
        } finally {
            accounting.flush()
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
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (stringId in uniqueStringIds) {
                accounting.consume()
                val actual = transformString(stringTable.get(stringId), transform)
                val matches = when (mode) {
                    StringMatchMode.EQUALS -> actual == expected
                    StringMatchMode.STARTS_WITH -> actual.startsWith(expected)
                    StringMatchMode.ENDS_WITH -> actual.endsWith(expected)
                    StringMatchMode.CONTAINS -> actual.contains(expected)
                }
                if (matches) matched[size++] = stringId
            }
        } finally {
            accounting.flush()
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
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (stringId in uniqueStringIds) {
                accounting.consume()
                if (!builder.add(stringId, stringTable.get(stringId))) return false
            }
        } finally {
            accounting.flush()
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

internal fun stringMatches(
    actual: String,
    transform: StringValueTransform?,
    mode: StringMatchMode,
    expected: String
): Boolean {
    val transformed = transformString(actual, transform)
    return when (mode) {
        StringMatchMode.EQUALS -> transformed == expected
        StringMatchMode.STARTS_WITH -> transformed.startsWith(expected)
        StringMatchMode.ENDS_WITH -> transformed.endsWith(expected)
        StringMatchMode.CONTAINS -> transformed.contains(expected)
    }
}

internal fun reusableContains(
    actual: MutableString,
    transform: StringValueTransform?,
    expected: String
): Boolean {
    if (transform == StringValueTransform.LOWERCASE) {
        var index = 0
        while (index < actual.length) {
            if (actual[index].code > ASCII_MAX_CODE) {
                return stringMatches(actual.toString(), transform, StringMatchMode.CONTAINS, expected)
            }
            index++
        }
        actual.toLowerCase()
    }
    return actual.indexOf(expected) >= 0
}

internal fun consumeGraphWork(consumer: GraphWorkConsumer?, workUnits: Long) {
    if (consumer == null || workUnits == 0L) return
    if (consumer is GraphWorkBatchConsumer) {
        consumer.consume(workUnits)
    } else {
        repeat(workUnits.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) { consumer.consume() }
    }
}

internal class BufferedGraphWorkConsumer(private val delegate: GraphWorkConsumer?) {
    private var pending = 0L

    fun consume() {
        if (delegate == null) return
        if (delegate !is GraphWorkBatchConsumer) {
            delegate.consume()
            return
        }
        pending++
        if (pending >= GRAPH_WORK_ACCOUNTING_BATCH_SIZE) flush()
    }

    fun flush() {
        if (pending == 0L) return
        val batch = pending
        pending = 0L
        consumeGraphWork(delegate, batch)
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
internal class ByteBufferDataInput(private val buf: ByteBuffer, private var position: Int) : DataInput {

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

private fun MessageDigest.updateIdentityInt(value: Int) {
    for (byteIndex in Int.SIZE_BYTES - 1 downTo 0) {
        update((value ushr (byteIndex * Byte.SIZE_BITS)).toByte())
    }
}

private fun MessageDigest.updateIdentityLong(value: Long) {
    for (byteIndex in Long.SIZE_BYTES - 1 downTo 0) {
        update((value ushr (byteIndex * Byte.SIZE_BITS)).toByte())
    }
}
