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
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.PreferredRawGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.ReleasableStringPropertyDisjunctionCache
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookupStrategy
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
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val callSiteScanWorkerNumber = AtomicInteger()
internal val callSiteScanParallelism: Int by lazy {
    val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    System.getProperty(CALL_SITE_SCAN_PARALLELISM_PROPERTY)
        ?.toIntOrNull()
        ?.coerceIn(1, processors)
        ?: processors
}
internal val callSiteScanExecutor by lazy {
    Executors.newFixedThreadPool(callSiteScanParallelism) { runnable ->
        Thread(runnable, "graphite-callsite-scan-${callSiteScanWorkerNumber.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}

private data class ParallelCallSiteScanResult(
    val workerIndex: Int,
    val matches: IntArray,
    val nodeIds: IntArray?,
    val propertyStringIds: Array<IntArray>?,
    val scannedCount: Int,
    val expectedCount: Int
) {
    val capturedCompleteIndex: Boolean
        get() = nodeIds != null && propertyStringIds != null && scannedCount == expectedCount
}

private data class ParallelCallSiteProjectionResult(
    val workerIndex: Int,
    val rows: List<StringPropertyDistinctRow>
)

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
    StringPropertyDisjunctionLookupStrategy,
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
    private val rawProjectionMatches = RawProjectionMatches()
    private val callSiteStringIndexLock = Any()
    private val callSiteStringIndexIdentityFile =
        callSiteStringIndexFile.resolveSibling(CALL_SITE_STRING_CONTENT_IDENTITY_FILE)
    private val callSiteTrigramPrefilterFile =
        callSiteStringIndexFile.resolveSibling(GraphStore.CALL_SITE_TRIGRAM_PREFILTER_FILE)
    private val callSiteTrigramPrefilterLock = Any()
    @Volatile
    private var callSiteTrigramPrefilter: MappedCallSiteTrigramPrefilter? = null
    @Volatile
    private var callSiteTrigramPrefilterUnavailable = false
    @Volatile
    private var callSiteStringIndex: MappedCallSiteStringIndex? = null
    private var callSiteStringIndexLoadedFromPersistence = false
    private val retainPersistedCallSiteStringIndex = AtomicBoolean()
    private var callSiteStringIndexPersistenceBudgetDenied = false
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
    private val callSiteParallelScanCount = AtomicLong()
    private val callSiteStringLookupEntryCount = AtomicLong()
    private val callSiteStringIndexLookupCount = AtomicLong()
    private val callSiteStringPreflightCount = AtomicLong()
    private val callSiteStringProjectionLookupCount = AtomicLong()
    private val callSiteScanActiveWorkers = AtomicInteger()
    private val callSiteScanPeakActiveWorkers = AtomicInteger()
    private val callSiteScanAbortedWorkers = AtomicLong()
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
        if (persistedTrigramPrefilterCannotMatch(predicates, workConsumer)) {
            return StringPropertyDisjunctionAggregate(
                count = 0,
                distinctValues = if (distinctProperty == null) null else emptySet()
            )
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
        return index.aggregate(predicates, distinctProperty, workConsumer)
    }

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
        val exactMatches = persistedTrigramPrefilterExactMatches(predicates, workConsumer)
        if (exactMatches?.all(IntArray::isEmpty) == true) return emptyList()
        if (exactMatches != null && workConsumer is SplitGraphWorkBatchConsumer) {
            parallelRawDistinctCallSiteStringProjection(
                predicates,
                projectedProperties,
                limit,
                selectedValues,
                workConsumer,
                exactMatches,
                callSiteTrigramPrefilter
            )?.let { return it }
        }
        retainPersistedCallSiteStringIndexForSplit(workConsumer)
        callSiteStringIndex?.let { index ->
            callSiteStringProjectionLookupCount.incrementAndGet()
            return index.distinctProjection(
                predicates,
                projectedProperties,
                limit,
                selectedValues,
                workConsumer
            )
        }
        if (workConsumer is SerialGraphWorkBatchConsumer || workConsumer is SplitGraphWorkBatchConsumer) {
            loadPersistedCallSiteStringIndexIfAvailable(type, workConsumer)?.let { index ->
                callSiteStringProjectionLookupCount.incrementAndGet()
                return index.distinctProjection(
                    predicates,
                    projectedProperties,
                    limit,
                    selectedValues,
                    workConsumer
                )
            }
        }
        if (serialCallSitePredicatesCannotMatch(predicates, workConsumer)) {
            return emptyList()
        }
        if (nonSerialCallSitePredicatesCannotMatch(predicates, workConsumer)) {
            return emptyList()
        }
        if (workConsumer is SerialGraphWorkBatchConsumer) {
            return rawDistinctCallSiteStringProjection(
                predicates,
                projectedProperties,
                limit,
                selectedValues,
                workConsumer
            )
        }
        if (workConsumer is SplitGraphWorkBatchConsumer) {
            parallelRawDistinctCallSiteStringProjection(
                predicates,
                projectedProperties,
                limit,
                selectedValues,
                workConsumer
            )?.let { return it }
        }
        val index = callSiteStringIndex(type, workConsumer) ?: return null
        return index.distinctProjection(
            predicates,
            projectedProperties,
            limit,
            selectedValues,
            workConsumer
        )
    }

    private fun serialCallSitePredicatesCannotMatch(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): Boolean = workConsumer is SerialGraphWorkBatchConsumer &&
        shouldPreflightCallSitePredicates(predicates) &&
        callSitePredicatesCannotMatch(predicates, workConsumer)

    private fun nonSerialCallSitePredicatesCannotMatch(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): Boolean = workConsumer !is SerialGraphWorkBatchConsumer &&
        shouldPreflightCallSitePredicates(predicates) &&
        callSitePredicatesCannotMatch(predicates, workConsumer)

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "NestedBlockDepth",
        "ThrowsCount",
        "TooGenericExceptionCaught"
    )
    private fun parallelRawDistinctCallSiteStringProjection(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        selectedValues: Set<List<String?>>?,
        workConsumer: SplitGraphWorkBatchConsumer,
        exactMatchingStringIds: List<IntArray>? = null,
        propertyStringFilter: MappedCallSiteTrigramPrefilter? = null
    ): List<StringPropertyDistinctRow>? {
        if (limit <= 0 || selectedValues?.isEmpty() == true) return emptyList()
        val nodeCount = nodeTypeIndex.count(CallSiteNode::class.java)
        if (nodeCount < MIN_PARALLEL_CALL_SITE_SCAN_NODES || nodeCount > Int.MAX_VALUE || limit >= nodeCount) {
            return null
        }
        val backgroundWorkerCount = minOf(
            callSiteScanParallelism,
            workConsumer.segmentWorkerCount,
            nodeCount.toInt() - 1
        )
        val workerCount = minOf(nodeCount.toInt(), backgroundWorkerCount + 1)
        val chunkSize = (nodeCount + workerCount - 1L) / workerCount
        val predicatePropertyIndexes = predicates.map { predicate ->
            requiredCallSiteStringPropertyIndex(predicate.property)
        }
        val projectedPropertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex)
        val selectedIdValues = selectedValues?.mapNotNullTo(hashSetOf()) { values ->
            if (values.size != projectedPropertyIndexes.size) return@mapNotNullTo null
            projectedPropertyIndexes.indices.map { index ->
                val propertyIndex = projectedPropertyIndexes[index]
                val value = values[index]
                when {
                    propertyIndex < 0 && value == null -> -1
                    propertyIndex < 0 || value == null -> return@mapNotNullTo null
                    else -> stringTable.findId(value).takeIf { stringId ->
                        stringId >= 0 &&
                            (propertyStringFilter == null ||
                                propertyStringFilter.containsPropertyStringId(propertyIndex, stringId))
                    } ?: return@mapNotNullTo null
                }
            }
        }
        if (selectedIdValues?.isEmpty() == true) return emptyList()
        val sharedStates = mutableMapOf<StringPredicateKey, ByteArray>()
        val matchStates = if (exactMatchingStringIds == null) {
            predicates.map { predicate ->
                sharedStates.getOrPut(StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)) {
                    ByteArray(stringTable.size())
                }
            }
        } else emptyList()
        val exactMatchSets = exactMatchingStringIds?.map(::IntOpenHashSet)
        val targetSize = minOf(limit, selectedValues?.size ?: limit)
        val abort = AtomicBoolean()
        val scanExecutor = if (backgroundWorkerCount > 0) {
            splitCallSiteExecutor(workConsumer.segmentWorkerCount)
        } else {
            callSiteScanExecutor
        }
        val completion = ExecutorCompletionService<ParallelCallSiteProjectionResult>(scanExecutor)
        val tasks = (0 until workerCount).mapNotNull { workerIndex ->
            val start = (workerIndex * chunkSize).toInt()
            val end = minOf(nodeCount, (workerIndex + 1L) * chunkSize).toInt()
            if (start >= end) return@mapNotNull null
            Callable {
                val activeWorkers = callSiteScanActiveWorkers.incrementAndGet()
                callSiteScanPeakActiveWorkers.accumulateAndGet(activeWorkers, ::maxOf)
                try {
                    val rows = ArrayList<StringPropertyDistinctRow>(targetSize)
                    val seenValues = HashSet<List<String?>>()
                    val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
                    val accounting = BufferedGraphWorkConsumer(workConsumer)
                    var inspected = 0
                    try {
                        nodeTypeIndex.forEachIdWhile(CallSiteNode::class.java, start, end) { nodeId ->
                            if ((inspected++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0 &&
                                (abort.get() || Thread.currentThread().isInterrupted)
                            ) {
                                if (abort.get()) callSiteScanAbortedWorkers.incrementAndGet()
                                throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
                            }
                            accounting.consume()
                            var matched = false
                            withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                                stringIds[CALLER_CLASS_PROPERTY_INDEX] = callerClass
                                stringIds[CALLER_NAME_PROPERTY_INDEX] = callerName
                                stringIds[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
                                stringIds[CALLEE_NAME_PROPERTY_INDEX] = calleeName
                                matched = predicates.indices.any { index ->
                                    val stringId = stringIds[predicatePropertyIndexes[index]]
                                    exactMatchSets?.let { sets -> return@any stringId in sets[index] }
                                    val states = matchStates[index]
                                    when (states[stringId]) {
                                        RAW_STRING_MATCH -> true
                                        RAW_STRING_MISS -> false
                                        else -> stringMatches(
                                            stringTable.get(stringId),
                                            predicates[index].transform,
                                            predicates[index].mode,
                                            predicates[index].expected
                                        ).also { result ->
                                            states[stringId] = if (result) RAW_STRING_MATCH else RAW_STRING_MISS
                                        }
                                    }
                                }
                            }
                            if (matched) {
                                if (selectedIdValues != null) {
                                    val ids = projectedPropertyIndexes.map { propertyIndex ->
                                        if (propertyIndex < 0) -1 else stringIds[propertyIndex]
                                    }
                                    if (ids !in selectedIdValues) return@forEachIdWhile true
                                }
                                val values = projectedPropertyIndexes.map { propertyIndex ->
                                    if (propertyIndex < 0) null else stringTable.get(stringIds[propertyIndex]).toString()
                                }
                                if (seenValues.add(values)) {
                                    rows += StringPropertyDistinctRow(nodeOffsets.offset(nodeId), values)
                                }
                            }
                            rows.size < targetSize
                        }
                    } finally {
                        accounting.flush()
                    }
                    ParallelCallSiteProjectionResult(workerIndex, rows)
                } catch (error: Throwable) {
                    abort.set(true)
                    throw error
                } finally {
                    callSiteScanActiveWorkers.decrementAndGet()
                }
            }
        }
        callSiteParallelScanCount.incrementAndGet()
        val inlineTask = tasks.first()
        tasks.drop(1).map(::trackedSplitCallSiteTask).forEach(completion::submit)
        val results = arrayOfNulls<ParallelCallSiteProjectionResult>(tasks.size)
        var received = 0
        var failure: Throwable? = null
        var interruption: InterruptedException? = null
        fun recordFailure(error: Throwable) {
            abort.set(true)
            if (failure == null || failure is CancellationException && error !is CancellationException) {
                failure = error
            }
        }
        try {
            val result = inlineTask.call()
            results[result.workerIndex] = result
        } catch (error: Throwable) {
            recordFailure(error)
        } finally {
            received++
        }
        while (received < tasks.size) {
            try {
                val result = completion.take().get()
                results[result.workerIndex] = result
                received++
            } catch (error: InterruptedException) {
                abort.set(true)
                if (interruption == null) interruption = error
            } catch (error: ExecutionException) {
                recordFailure(error.cause ?: error)
                received++
            }
        }
        interruption?.let { error ->
            Thread.currentThread().interrupt()
            throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED).apply { initCause(error) }
        }
        failure?.let { error -> throw error }
        val rows = ArrayList<StringPropertyDistinctRow>(targetSize)
        val seenValues = HashSet<List<String?>>()
        results.filterNotNull().sortedBy(ParallelCallSiteProjectionResult::workerIndex).forEach { result ->
            result.rows.forEach { row ->
                if (seenValues.add(row.values)) rows += row
                if (rows.size >= targetSize) return rows
            }
        }
        return rows
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth")
    private fun rawDistinctCallSiteStringProjection(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        selectedValues: Set<List<String?>>?,
        workConsumer: GraphWorkConsumer
    ): List<StringPropertyDistinctRow> {
        if (limit <= 0 || selectedValues?.isEmpty() == true) return emptyList()
        val predicatePropertyIndexes = predicates.map { predicate ->
            requiredCallSiteStringPropertyIndex(predicate.property)
        }
        val projectedPropertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex)
        val sharedStates = mutableMapOf<StringPredicateKey, ByteArray>()
        val matchStates = predicates.map { predicate ->
            sharedStates.getOrPut(StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)) {
                ByteArray(stringTable.size())
            }
        }
        val rows = mutableListOf<StringPropertyDistinctRow>()
        val seenValues = HashSet<List<String?>>()
        val targetSize = minOf(limit, selectedValues?.size ?: limit)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        var inspected = 0
        try {
            for (nodeId in nodeTypeIndex.ids(CallSiteNode::class.java)) {
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
                    matched = predicates.indices.any { index ->
                        val stringId = stringIds[predicatePropertyIndexes[index]]
                        val states = matchStates[index]
                        when (states[stringId]) {
                            RAW_STRING_MATCH -> true
                            RAW_STRING_MISS -> false
                            else -> stringMatches(
                                stringTable.get(stringId),
                                predicates[index].transform,
                                predicates[index].mode,
                                predicates[index].expected
                            ).also { result ->
                                states[stringId] = if (result) RAW_STRING_MATCH else RAW_STRING_MISS
                            }
                        }
                    }
                }
                if (!matched) continue
                val values = projectedPropertyIndexes.map { propertyIndex ->
                    if (propertyIndex < 0) null else stringTable.get(stringIds[propertyIndex]).toString()
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
        if (workConsumer is PreferredRawGraphWorkBatchConsumer && callSiteStringIndex == null) {
            rawCallSiteStringProjection(predicates, projectedProperties, limit, workConsumer)?.let { return it }
        }
        val index = callSiteStringIndex ?: return null
        callSiteStringIndexLookupCount.incrementAndGet()
        return index.projectRows(predicates, projectedProperties, limit, workConsumer)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun rawCallSiteStringProjection(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        workConsumer: GraphWorkConsumer
    ): List<StringPropertyProjectionRow>? {
        if (limit <= 0) return emptyList()
        callSiteStringLookupEntryCount.incrementAndGet()
        val cacheKey = RawProjectionMatchKey(predicates.toList(), limit)
        val predicatePropertyIndexes = predicates.map { predicate ->
            requiredCallSiteStringPropertyIndex(predicate.property)
        }
        val projectedPropertyIndexes = projectedProperties.map(::requiredCallSiteStringPropertyIndex)
        rawProjectionMatches[cacheKey]?.let { cachedNodeIds ->
            consumeGraphWork(workConsumer, cachedNodeIds.size.coerceAtLeast(1).toLong())
            return projectRawCallSiteRows(cachedNodeIds, projectedPropertyIndexes)
        }
        val sharedStates = mutableMapOf<StringPredicateKey, BoundedStringMatcher>()
        val matchStates = predicates.map { predicate ->
            sharedStates.getOrPut(StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)) {
                BoundedStringMatcher(stringTable, StringPredicateKey(
                    predicate.transform,
                    predicate.mode,
                    predicate.expected
                ), RAW_PROJECTION_STRING_MATCH_CACHE_CAPACITY)
            }
        }
        val rows = ArrayList<StringPropertyProjectionRow>(limit)
        val matchedNodeIds = IntArray(limit)
        val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        val nodeIds = nodeTypeIndex.ids(CallSiteNode::class.java).iterator()
        val scaledProbe = (limit.toLong() * RAW_PROJECTION_PROBE_FACTOR)
            .coerceAtMost(RAW_PROJECTION_MAX_PROBE_NODES.toLong())
            .toInt()
        val maxInspected = minOf(
            RAW_PROJECTION_MAX_PROBE_NODES,
            maxOf(RAW_PROJECTION_MIN_PROBE_NODES, scaledProbe)
        )
        var inspected = 0
        try {
            while (nodeIds.hasNext() && inspected < maxInspected) {
                val nodeId = nodeIds.next()
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
                    matched = predicates.indices.any { index ->
                        val stringId = stringIds[predicatePropertyIndexes[index]]
                        matchStates[index].matches(stringId)
                    }
                }
                if (!matched) continue
                matchedNodeIds[rows.size] = nodeId
                rows += StringPropertyProjectionRow(projectedPropertyIndexes.map { propertyIndex ->
                    stringTable.get(stringIds[propertyIndex]).toString()
                })
                if (rows.size >= limit) break
            }
            if (rows.size < limit && nodeIds.hasNext()) return null
            rawProjectionMatches.put(cacheKey, matchedNodeIds.copyOf(rows.size))
            return rows
        } finally {
            accounting.flush()
        }
    }

    private fun projectRawCallSiteRows(
        nodeIds: IntArray,
        projectedPropertyIndexes: List<Int>
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
                stringTable.get(stringIds[propertyIndex]).toString()
            })
        }
    }

    @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod", "ReturnCount")
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
            if (workConsumer is PreferredRawGraphWorkBatchConsumer) {
                serialRawCallSiteStringDisjunction<T>(type, predicates, limit, workConsumer)?.let { return it }
            }
            val exactMatches = persistedTrigramPrefilterExactMatches(predicates, workConsumer)
            if (exactMatches?.all(IntArray::isEmpty) == true) return emptySequence()
            if (exactMatches != null && workConsumer is SplitGraphWorkBatchConsumer) {
                parallelRawCallSiteStringDisjunction<T>(
                    type,
                    predicates,
                    limit,
                    workConsumer,
                    exactMatches
                )?.let { return it }
            }
            retainPersistedCallSiteStringIndexForSplit(workConsumer)
            callSiteStringIndex?.let { index ->
                callSiteStringIndexLookupCount.incrementAndGet()
                return index.matchingNodeIds(predicates, workConsumer, limit)
                    .mapNotNull { nodeId -> node(NodeId(nodeId)) as? CallSiteNode }
                    .map(type::cast)
            }
            if (workConsumer is SplitGraphWorkBatchConsumer) {
                loadPersistedCallSiteStringIndexIfAvailable(type, workConsumer)?.let { index ->
                    callSiteStringIndexLookupCount.incrementAndGet()
                    return index.matchingNodeIds(predicates, workConsumer, limit)
                        .mapNotNull { nodeId -> node(NodeId(nodeId)) as? CallSiteNode }
                        .map(type::cast)
                }
            }
            parallelRawCallSiteStringDisjunction<T>(type, predicates, limit, workConsumer)?.let { return it }
            serialRawCallSiteStringDisjunction<T>(type, predicates, limit, workConsumer)?.let { return it }
        }
        if (type == CallSiteNode::class.java && shouldPreflightCallSitePredicates(predicates) &&
            callSitePredicatesCannotMatch(predicates, workConsumer)
        ) return emptySequence()
        if (workConsumer !is SerialGraphWorkBatchConsumer) {
            callSiteStringIndex(type, workConsumer)?.let { index ->
                return index.matchingNodeIds(predicates, workConsumer, limit)
                    .mapNotNull { nodeId -> node(NodeId(nodeId)) as? CallSiteNode }
                    .map(type::cast)
            }
        }
        if (prefersSerialStringPropertyDisjunction(type, predicates)) return null
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
        if (workConsumer !is SerialGraphWorkBatchConsumer) return null
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

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount", "TooGenericExceptionCaught")
    private fun <T : Node> parallelRawCallSiteStringDisjunction(
        type: Class<T>,
        predicates: List<StringPropertyPredicate>,
        limit: Int,
        workConsumer: GraphWorkConsumer?,
        exactMatchingStringIds: List<IntArray>? = null
    ): Sequence<T>? {
        if (workConsumer !is ParallelGraphWorkBatchConsumer) return null
        val splitWork = workConsumer as? SplitGraphWorkBatchConsumer
        if (type != CallSiteNode::class.java || limit == Int.MAX_VALUE ||
            (callSiteScanParallelism <= 1 && splitWork == null)
        ) return null
        val nodeCount = nodeTypeIndex.count(type)
        if (nodeCount < MIN_PARALLEL_CALL_SITE_SCAN_NODES || nodeCount > Int.MAX_VALUE || limit >= nodeCount) return null

        val backgroundWorkerCount = minOf(
            callSiteScanParallelism,
            splitWork?.segmentWorkerCount ?: callSiteScanParallelism,
            nodeCount.toInt() - if (splitWork == null) 0 else 1
        )
        val workerCount = minOf(
            nodeCount.toInt(),
            backgroundWorkerCount + if (splitWork == null) 0 else 1
        )
        val chunkSize = (nodeCount + workerCount - 1L) / workerCount
        val predicatePropertyIndexes = predicates.map { predicate ->
            requiredCallSiteStringPropertyIndex(predicate.property)
        }
        val sharedStates = mutableMapOf<StringPredicateKey, ByteArray>()
        val matchStates = if (exactMatchingStringIds == null) {
            predicates.map { predicate ->
                sharedStates.getOrPut(StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)) {
                    ByteArray(stringTable.size())
                }
            }
        } else emptyList()
        val exactMatchSets = exactMatchingStringIds?.map(::IntOpenHashSet)
        val scanRanges = (0 until workerCount).mapNotNull { workerIndex ->
            val start = (workerIndex * chunkSize).toInt()
            val end = minOf(nodeCount, (workerIndex + 1L) * chunkSize).toInt()
            if (start >= end) return@mapNotNull null
            Triple(workerIndex, start, end)
        }
        // A cross-graph cold query must pay only for the requested scan. Building an index in every
        // graph multiplies the first-query work and defeats the additive graph/segment split.
        var indexReservation = if (splitWork == null && callSiteStringIndex == null) {
            estimatedMappedCallSiteStringIndexCountBytes(stringTable.size())
                ?.let(MappedCallSiteStringIndexMemoryBudget::tryReserve)
        } else {
            null
        }
        val abort = AtomicBoolean()
        val scanExecutor = if (splitWork != null && backgroundWorkerCount > 0) {
            splitCallSiteExecutor(splitWork.segmentWorkerCount)
        } else {
            callSiteScanExecutor
        }
        val completion = ExecutorCompletionService<ParallelCallSiteScanResult>(scanExecutor)
        val tasks = scanRanges.map { (workerIndex, start, end) ->
            Callable {
                val activeWorkers = callSiteScanActiveWorkers.incrementAndGet()
                callSiteScanPeakActiveWorkers.accumulateAndGet(activeWorkers, ::maxOf)
                try {
                    val expectedCount = end - start
                    val matches = IntArrayList(minOf(limit, end - start))
                    val capturedNodeIds = indexReservation?.let { IntArray(expectedCount) }
                    val capturedStringIds = indexReservation?.let {
                        Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(expectedCount) }
                    }
                    val accounting = BufferedGraphWorkConsumer(workConsumer)
                    var inspected = 0
                    try {
                        nodeTypeIndex.forEachIdWhile(type, start, end) { nodeId ->
                            if ((inspected and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0 &&
                                (abort.get() || Thread.currentThread().isInterrupted)
                            ) {
                                if (abort.get()) callSiteScanAbortedWorkers.incrementAndGet()
                                throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
                            }
                            accounting.consume()
                            var matched = false
                            withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                                capturedNodeIds?.set(inspected, nodeId)
                                capturedStringIds?.let { stringIds ->
                                    stringIds[CALLER_CLASS_PROPERTY_INDEX][inspected] = callerClass
                                    stringIds[CALLER_NAME_PROPERTY_INDEX][inspected] = callerName
                                    stringIds[CALLEE_CLASS_PROPERTY_INDEX][inspected] = calleeClass
                                    stringIds[CALLEE_NAME_PROPERTY_INDEX][inspected] = calleeName
                                }
                                matched = predicates.indices.any { index ->
                                    val predicate = predicates[index]
                                    val stringId = when (predicatePropertyIndexes[index]) {
                                        CALLER_CLASS_PROPERTY_INDEX -> callerClass
                                        CALLER_NAME_PROPERTY_INDEX -> callerName
                                        CALLEE_CLASS_PROPERTY_INDEX -> calleeClass
                                        else -> calleeName
                                    }
                                    exactMatchSets?.let { sets -> return@any stringId in sets[index] }
                                    val states = matchStates[index]
                                    when (states[stringId]) {
                                        RAW_STRING_MATCH -> true
                                        RAW_STRING_MISS -> false
                                        else -> stringMatches(
                                            stringTable.get(stringId),
                                            predicate.transform,
                                            predicate.mode,
                                            predicate.expected
                                        ).also { result ->
                                            // A stale byte read only repeats the same deterministic verification.
                                            states[stringId] = if (result) RAW_STRING_MATCH else RAW_STRING_MISS
                                        }
                                    }
                                }
                            }
                            inspected++
                            if (matched) {
                                matches.add(nodeId)
                            }
                            matches.size != limit
                        }
                        ParallelCallSiteScanResult(
                            workerIndex,
                            matches.toIntArray(),
                            capturedNodeIds,
                            capturedStringIds,
                            inspected,
                            expectedCount
                        )
                    } finally {
                        accounting.flush()
                    }
                } catch (error: Throwable) {
                    abort.set(true)
                    throw error
                } finally {
                    callSiteScanActiveWorkers.decrementAndGet()
                }
            }
        }
        callSiteParallelScanCount.incrementAndGet()
        val inlineTask = tasks.firstOrNull().takeIf { splitWork != null }
        val backgroundTasks = if (inlineTask == null) tasks else tasks.drop(1)
        backgroundTasks.map { task ->
            if (splitWork == null) task else trackedSplitCallSiteTask(task)
        }.forEach(completion::submit)
        val results = arrayOfNulls<ParallelCallSiteScanResult>(tasks.size)
        var received = 0
        var failure: Throwable? = null
        var interruption: InterruptedException? = null
        fun recordFailure(error: Throwable) {
            abort.set(true)
            if (failure == null || failure is CancellationException && error !is CancellationException) {
                failure = error
            }
        }
        inlineTask?.let { task ->
            try {
                val workerResult = task.call()
                results[workerResult.workerIndex] = workerResult
            } catch (error: Throwable) {
                recordFailure(error)
            } finally {
                received++
            }
        }
        while (received < tasks.size) {
            try {
                val workerResult = completion.take().get()
                results[workerResult.workerIndex] = workerResult
                received++
            } catch (error: InterruptedException) {
                abort.set(true)
                if (interruption == null) interruption = error
            } catch (error: ExecutionException) {
                recordFailure(error.cause ?: error)
                received++
            }
        }
        interruption?.let { error ->
            indexReservation?.close()
            indexReservation = null
            Thread.currentThread().interrupt()
            throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED).apply { initCause(error) }
        }
        failure?.let { error ->
            indexReservation?.close()
            indexReservation = null
            throw error
        }
        indexReservation?.let { reservation ->
            indexReservation = null
            val completed = results.filterNotNull()
            if (completed.size == tasks.size && completed.all(ParallelCallSiteScanResult::capturedCompleteIndex)) {
                try {
                    buildAndPublishCallSiteStringIndex(completed, nodeCount, reservation, workConsumer)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The bounded scan already produced a complete query result. Index publication is
                    // an optional cache handoff, so a budget/admission/build failure must not replace it.
                }
            } else {
                reservation.close()
            }
        }
        val matchedNodeIds = results.asSequence().filterNotNull()
            .flatMap { result -> result.matches.asSequence() }.take(limit)
        return matchedNodeIds.mapNotNull { nodeId -> node(NodeId(nodeId))?.let(type::cast) }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
    private fun buildAndPublishCallSiteStringIndex(
        scanResults: List<ParallelCallSiteScanResult>,
        nodeCount: Long,
        reservation: MappedCallSiteStringIndexMemoryBudget.Reservation,
        workConsumer: GraphWorkConsumer?
    ) {
        try {
            val stringCount = stringTable.size()
            val endsByStringId = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(stringCount) }
            val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
            forEachCallSiteStringProperty(workConsumer) { propertyIndex, abort ->
                val countAccounting = BufferedGraphWorkConsumer(
                    workConsumer.takeIf { propertyIndex == 0 }
                )
                val ends = endsByStringId[propertyIndex]
                try {
                    scanResults.forEach { result ->
                        val stringIds = checkNotNull(result.propertyStringIds)[propertyIndex]
                        repeat(result.scannedCount) { index ->
                            checkCallSiteIndexBuildWorker(index, abort)
                            countAccounting.consume()
                            ends[stringIds[index]]++
                        }
                    }
                } finally {
                    countAccounting.flush()
                }
                uniqueCounts[propertyIndex] = ends.count { count -> count > 0 }
            }
            val retainedBytes = estimatedMappedCallSiteStringIndexRetainedBytes(
                nodeCount,
                stringCount,
                uniqueCounts
            ) ?: run {
                reservation.close()
                return
            }
            if (!reservation.tryGrowTo(retainedBytes)) {
                reservation.close()
                return
            }
            val capacity = nodeCount.toInt()
            val usedStringIds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                IntArray(uniqueCounts[propertyIndex])
            }
            val postingEnds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                IntArray(uniqueCounts[propertyIndex])
            }
            val postings = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(capacity) }
            forEachCallSiteStringProperty(workConsumer) { propertyIndex, abort ->
                val ends = endsByStringId[propertyIndex]
                val used = usedStringIds[propertyIndex]
                var usedIndex = 0
                var postingOffset = 0
                for (stringId in ends.indices) {
                    checkCallSiteIndexBuildWorker(stringId, abort)
                    val count = ends[stringId]
                    if (count == 0) continue
                    used[usedIndex++] = stringId
                    ends[stringId] = postingOffset
                    postingOffset += count
                }
                check(usedIndex == used.size && postingOffset == capacity)
                val postingAccounting = BufferedGraphWorkConsumer(
                    workConsumer.takeIf { propertyIndex == 0 }
                )
                try {
                    scanResults.forEach { result ->
                        val nodeIds = checkNotNull(result.nodeIds)
                        val stringIds = checkNotNull(result.propertyStringIds)[propertyIndex]
                        repeat(result.scannedCount) { index ->
                            checkCallSiteIndexBuildWorker(index, abort)
                            postingAccounting.consume()
                            postings[propertyIndex][ends[stringIds[index]]++] = nodeIds[index]
                        }
                    }
                } finally {
                    postingAccounting.flush()
                }
                val compactEnds = postingEnds[propertyIndex]
                compactEnds.indices.forEach { row ->
                    checkCallSiteIndexBuildWorker(row, abort)
                    compactEnds[row] = ends[used[row]]
                }
            }
            reservation.shrinkTo(retainedBytes)
            val contentIdentity = callSiteStringIndexContentIdentity(scanResults, nodeCount.toInt())
            val built = MappedCallSiteStringIndex(
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
                contentIdentity = { contentIdentity.copyOf() },
                reservation = reservation
            )
            val published = synchronized(callSiteStringIndexLock) {
                if (callSiteStringIndex == null) {
                    callSiteStringIndex = built
                    callSiteStringIndexLoadedFromPersistence = false
                    true
                } else {
                    false
                }
            }
            if (!published) built.close()
        } catch (error: Throwable) {
            reservation.close()
            throw error
        }
    }

    private fun callSiteStringIndexContentIdentity(
        scanResults: List<ParallelCallSiteScanResult>,
        callSiteCount: Int
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(stringTable.contentIdentity())
        digest.updateIdentityInt(callSiteCount)
        scanResults.forEach { result ->
            val nodeIds = checkNotNull(result.nodeIds)
            val stringIds = checkNotNull(result.propertyStringIds)
            repeat(result.scannedCount) { index ->
                val nodeId = nodeIds[index]
                digest.updateIdentityInt(nodeId)
                digest.updateIdentityLong(nodeOffsets.offset(nodeId))
                repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                    digest.updateIdentityInt(stringIds[propertyIndex][index])
                }
            }
        }
        return digest.digest()
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    private fun prepareCallSiteStringIndexInParallel(
        workConsumer: GraphWorkConsumer?
    ): Boolean {
        if (callSiteScanParallelism <= 1 || workConsumer !is ParallelGraphWorkBatchConsumer) return false
        val nodeCount = nodeTypeIndex.count(CallSiteNode::class.java)
        if (nodeCount < MIN_PARALLEL_CALL_SITE_SCAN_NODES || nodeCount > Int.MAX_VALUE) return false
        val reservation = estimatedMappedCallSiteStringIndexCountBytes(stringTable.size())
            ?.let(MappedCallSiteStringIndexMemoryBudget::tryReserve)
            ?: return false
        val workerCount = minOf(callSiteScanParallelism, nodeCount.toInt())
        val chunkSize = (nodeCount + workerCount - 1L) / workerCount
        val abort = AtomicBoolean()
        val completion = ExecutorCompletionService<ParallelCallSiteScanResult>(callSiteScanExecutor)
        val tasks = (0 until workerCount).mapNotNull { workerIndex ->
            val start = (workerIndex * chunkSize).toInt()
            val end = minOf(nodeCount, (workerIndex + 1L) * chunkSize).toInt()
            if (start >= end) return@mapNotNull null
            Callable {
                val activeWorkers = callSiteScanActiveWorkers.incrementAndGet()
                callSiteScanPeakActiveWorkers.accumulateAndGet(activeWorkers, ::maxOf)
                try {
                    val expectedCount = end - start
                    val nodeIds = IntArray(expectedCount)
                    val propertyStringIds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(expectedCount) }
                    val accounting = BufferedGraphWorkConsumer(workConsumer)
                    var inspected = 0
                    try {
                        nodeTypeIndex.forEachIdWhile(CallSiteNode::class.java, start, end) { nodeId ->
                            checkCallSiteIndexBuildWorker(inspected, abort)
                            accounting.consume()
                            nodeIds[inspected] = nodeId
                            withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                                propertyStringIds[CALLER_CLASS_PROPERTY_INDEX][inspected] = callerClass
                                propertyStringIds[CALLER_NAME_PROPERTY_INDEX][inspected] = callerName
                                propertyStringIds[CALLEE_CLASS_PROPERTY_INDEX][inspected] = calleeClass
                                propertyStringIds[CALLEE_NAME_PROPERTY_INDEX][inspected] = calleeName
                            }
                            inspected++
                            true
                        }
                    } finally {
                        accounting.flush()
                    }
                    ParallelCallSiteScanResult(
                        workerIndex,
                        IntArray(0),
                        nodeIds,
                        propertyStringIds,
                        inspected,
                        expectedCount
                    )
                } catch (error: Throwable) {
                    abort.set(true)
                    throw error
                } finally {
                    callSiteScanActiveWorkers.decrementAndGet()
                }
            }
        }
        callSiteParallelScanCount.incrementAndGet()
        tasks.forEach(completion::submit)
        val results = arrayOfNulls<ParallelCallSiteScanResult>(tasks.size)
        var received = 0
        var failure: Throwable? = null
        var interruption: InterruptedException? = null
        while (received < tasks.size) {
            try {
                val result = completion.take().get()
                results[result.workerIndex] = result
                received++
            } catch (error: InterruptedException) {
                abort.set(true)
                if (interruption == null) interruption = error
            } catch (error: ExecutionException) {
                abort.set(true)
                val cause = error.cause ?: error
                if (failure == null || failure is CancellationException && cause !is CancellationException) {
                    failure = cause
                }
                received++
            }
        }
        interruption?.let { error ->
            reservation.close()
            Thread.currentThread().interrupt()
            throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED).apply { initCause(error) }
        }
        failure?.let { error ->
            reservation.close()
            throw error
        }
        val completed = results.filterNotNull()
        if (completed.size != tasks.size || completed.any { !it.capturedCompleteIndex }) {
            reservation.close()
            return false
        }
        buildAndPublishCallSiteStringIndex(completed, nodeCount, reservation, workConsumer)
        return callSiteStringIndex != null
    }

    @Suppress("CyclomaticComplexMethod", "InstanceOfCheckForException", "ThrowsCount", "TooGenericExceptionCaught")
    private fun forEachCallSiteStringProperty(
        workConsumer: GraphWorkConsumer?,
        action: (propertyIndex: Int, abort: AtomicBoolean) -> Unit
    ) {
        val abort = AtomicBoolean()
        if (workConsumer !is ParallelGraphWorkBatchConsumer || callSiteScanParallelism <= 1) {
            repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> action(propertyIndex, abort) }
            return
        }
        val completion = ExecutorCompletionService<Unit>(callSiteScanExecutor)
        val primaryFailure = AtomicReference<Throwable>()
        repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
            completion.submit(Callable {
                try {
                    action(propertyIndex, abort)
                } catch (error: Throwable) {
                    if (abort.compareAndSet(false, true) || error !is CancellationException) {
                        primaryFailure.compareAndSet(null, error)
                    }
                    throw error
                }
            })
        }
        var received = 0
        var failure: Throwable? = null
        var interruption: InterruptedException? = null
        while (received < CALL_SITE_STRING_PROPERTY_COUNT) {
            try {
                completion.take().get()
                received++
            } catch (error: InterruptedException) {
                abort.set(true)
                if (interruption == null) interruption = error
            } catch (error: ExecutionException) {
                abort.set(true)
                val cause = error.cause ?: error
                if (failure == null || failure is CancellationException && cause !is CancellationException) {
                    failure = cause
                }
                received++
            }
        }
        interruption?.let { error ->
            Thread.currentThread().interrupt()
            throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED).apply { initCause(error) }
        }
        primaryFailure.get()?.let { throw it }
        failure?.let { throw it }
    }

    private fun checkCallSiteIndexBuildWorker(index: Int, abort: AtomicBoolean) {
        if ((index and RAW_SCAN_INTERRUPTION_POLL_MASK) != 0) return
        if (abort.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
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

    private fun persistedTrigramPrefilterCannotMatch(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): Boolean = persistedTrigramPrefilterExactMatches(predicates, workConsumer)
        ?.all(IntArray::isEmpty) == true

    private fun persistedTrigramPrefilterExactMatches(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): List<IntArray>? {
        if (callSiteStringIndex != null || workConsumer !is SplitGraphWorkBatchConsumer || predicates.isEmpty()) {
            return null
        }
        callSiteTrigramPrefilter?.let { prefilter ->
            return prefilter.exactMatchingStringIds(predicates, workConsumer)
        }
        if (callSiteTrigramPrefilterUnavailable) return null
        val prefilter = synchronized(callSiteTrigramPrefilterLock) {
            callSiteTrigramPrefilter ?: run {
                if (callSiteTrigramPrefilterUnavailable) return@synchronized null
                val identity = runCatching { Files.readAllBytes(callSiteStringIndexIdentityFile) }.getOrNull()
                val loaded = identity?.let { expected ->
                    MappedCallSiteTrigramPrefilter.load(
                        callSiteTrigramPrefilterFile,
                        callSiteStringIndexFile,
                        stringTable.size(),
                        nodeTypeIndex.count(CallSiteNode::class.java).toInt(),
                        expected,
                        stringTable,
                        workConsumer
                    )
                }
                if (loaded == null) {
                    callSiteTrigramPrefilterUnavailable = true
                } else {
                    callSiteTrigramPrefilter = loaded
                }
                loaded
            }
        } ?: return null
        return prefilter.exactMatchingStringIds(predicates, workConsumer)
    }

    @Suppress("ReturnCount")
    override fun prefersSerialStringPropertyDisjunction(
        type: Class<out Node>,
        predicates: List<StringPropertyPredicate>
    ): Boolean {
        callSiteStringIndex?.let { index ->
            if (type == CallSiteNode::class.java &&
                predicates.all { supportsRawStringProperty(type, it.property) }
            ) return index.prefersSerialScan
        }
        return false
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
            callSiteStringIndex?.let { index ->
                val predicate = StringPropertyPredicate(property, transform, mode, expected)
                return index.matchingNodeIds(listOf(predicate), workConsumer)
                    .take(limit)
                    .mapNotNull { nodeId -> node(NodeId(nodeId)) as? T }
            }
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
        return methodIndex().slice(pattern, limit, scanConsumer)
    }

    private fun streamMethods(
        pattern: MethodPattern,
        scanConsumer: MethodMetadataScanConsumer? = null
    ): Sequence<MethodDescriptor> = sequence {
        yieldAll(methodIndex().methods(pattern, scanConsumer))
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
        synchronized(callSiteTrigramPrefilterLock) {
            callSiteTrigramPrefilter = null
            callSiteTrigramPrefilterUnavailable = false
        }
        synchronized(stringPropertyIndexLock) {
            stringPropertyIndexes.clear()
            stringPropertyAdmissions.clear()
            rawStringMatchStates.clear()
            rawProjectionMatches.clear()
        }
    }

    override fun releaseStringPropertyDisjunctionCache() {
        closeCallSiteStringIndex(force = false)
    }

    private fun closeCallSiteStringIndex(force: Boolean) {
        synchronized(callSiteStringIndexLock) {
            // Split cross-graph waves reuse the sidecar across queries; serial callers preserve the
            // prior release behavior so a warmup cannot turn a cheap zero-hit preflight into an
            // indexed projection during the measured query.
            if (!force && callSiteStringIndexLoadedFromPersistence &&
                retainPersistedCallSiteStringIndex.get()
            ) return
            val index = callSiteStringIndex
            if (persistentCallSiteStringIndexEnabled &&
                !callSiteStringIndexLoadedFromPersistence &&
                index?.isTrigramPostingsInitialized() == true
            ) {
                persistPreparedCallSiteStringIndex()
            }
            callSiteStringIndex?.close()
            callSiteStringIndex = null
            callSiteStringIndexLoadedFromPersistence = false
            retainPersistedCallSiteStringIndex.set(false)
        }
    }

    private fun retainPersistedCallSiteStringIndexForSplit(workConsumer: GraphWorkConsumer?) {
        if (workConsumer is SplitGraphWorkBatchConsumer) retainPersistedCallSiteStringIndex.set(true)
    }

    internal fun rawStringMatchStateBytes(): Long = rawStringMatchStates.retainedBytes()

    internal fun rawStringMatchStateCount(): Int = rawStringMatchStates.size()

    internal fun rawProjectionMatchCount(): Int = rawProjectionMatches.size()

    internal fun callSiteStringIndexBytes(): Long = callSiteStringIndex?.retainedBytes ?: 0L

    internal fun hasExactCallSiteProjectionTupleIndex(): Boolean =
        callSiteStringIndex?.hasExactProjectionTupleIndex() == true

    internal fun isCallSiteStringIndexInitialized(): Boolean = callSiteStringIndex != null

    internal fun isCallSiteTrigramIndexInitialized(): Boolean =
        callSiteStringIndex?.isTrigramPostingsInitialized() == true

    internal fun isCallSiteStringIndexLoadedFromPersistence(): Boolean =
        callSiteStringIndexLoadedFromPersistence

    internal fun isCallSiteTrigramPrefilterInitialized(): Boolean = callSiteTrigramPrefilter != null

    /**
     * Prepares the complete CallSite string search path before the graph is exposed to queries.
     * A denied shared-memory reservation is a normal fallback and leaves raw scans available.
     */
    internal fun prepareCallSiteStringIndex(
        workConsumer: GraphWorkConsumer = CALL_SITE_INDEX_PREPARATION_WORK_CONSUMER
    ): Boolean {
        val index = if (!Files.isRegularFile(callSiteStringIndexFile) &&
            prepareCallSiteStringIndexInParallel(workConsumer)
        ) {
            callSiteStringIndex
        } else {
            callSiteStringIndex(CallSiteNode::class.java, workConsumer)
        } ?: return false
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
            replaceAtomically(temporary, callSiteStringIndexFile)
            temporary = null
            persistCallSiteTrigramPrefilter(index, callSiteStringIndexFile, callSiteTrigramPrefilterFile)
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

    internal fun callSiteParallelScanCount(): Long = callSiteParallelScanCount.get()

    internal fun callSiteStringLookupEntryCount(): Long = callSiteStringLookupEntryCount.get()

    internal fun callSiteStringIndexLookupCount(): Long = callSiteStringIndexLookupCount.get()

    internal fun callSiteStringPreflightCount(): Long = callSiteStringPreflightCount.get()

    internal fun callSiteStringProjectionLookupCount(): Long = callSiteStringProjectionLookupCount.get()

    internal fun callSiteScanPeakActiveWorkers(): Int = callSiteScanPeakActiveWorkers.get()

    internal fun callSiteSegmentPeakActiveWorkers(): Int = splitCallSitePeakActiveWorkers()

    internal fun callSiteScanActiveWorkers(): Int = callSiteScanActiveWorkers.get()

    internal fun callSiteScanAbortedWorkers(): Long = callSiteScanAbortedWorkers.get()

    internal fun resetCallSiteScanMetrics() {
        check(callSiteScanActiveWorkers.get() == 0) { "Cannot reset active CallSite scan metrics" }
        resetSplitCallSiteWorkerMetrics()
        callSiteParallelScanCount.set(0L)
        callSiteStringLookupEntryCount.set(0L)
        callSiteStringIndexLookupCount.set(0L)
        callSiteStringPreflightCount.set(0L)
        callSiteStringProjectionLookupCount.set(0L)
        callSiteScanPeakActiveWorkers.set(0)
        callSiteScanAbortedWorkers.set(0L)
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

    /**
     * Restores a production-built sidecar for a cross-graph scan without falling through to the
     * serial in-memory builder. A missing, corrupt, or budget-denied sidecar deliberately returns
     * null so the caller can use the additive graph/segment raw-scan fallback.
     */
    private fun loadPersistedCallSiteStringIndexIfAvailable(
        type: Class<out Node>,
        workConsumer: GraphWorkConsumer
    ): MappedCallSiteStringIndex? {
        if (type != CallSiteNode::class.java || !persistentCallSiteStringIndexEnabled ||
            !Files.isRegularFile(callSiteStringIndexFile)
        ) {
            return null
        }
        callSiteStringIndex?.let { return it }
        return synchronized(callSiteStringIndexLock) {
            callSiteStringIndex?.let { return@synchronized it }
            val nodeCount = nodeTypeIndex.count(CallSiteNode::class.java)
            if (nodeCount <= 0L || nodeCount > Int.MAX_VALUE) return@synchronized null
            loadPersistedCallSiteStringIndex(nodeCount.toInt(), workConsumer)?.also { persisted ->
                callSiteStringIndex = persisted
                callSiteStringIndexLoadedFromPersistence = true
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
private const val CALL_SITE_SCAN_PARALLELISM_PROPERTY = "graphite.webgraph.callSiteScanParallelism"
private val CALL_SITE_INDEX_PREPARATION_WORK_CONSUMER = ParallelGraphWorkBatchConsumer { _ -> }
private const val MIN_PARALLEL_CALL_SITE_SCAN_NODES = 4_096L
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
private const val RAW_PROJECTION_MAX_PROBE_NODES = 1_024
private const val RAW_PROJECTION_PROBE_FACTOR = 4
private const val MAX_RAW_PROJECTION_MATCH_ENTRIES = 16
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

private class RawProjectionMatches {
    private val matches = LinkedHashMap<RawProjectionMatchKey, IntArray>(
        MAX_RAW_PROJECTION_MATCH_ENTRIES + 1,
        STRING_PROPERTY_INDEX_LOAD_FACTOR,
        true
    )

    @Synchronized
    operator fun get(key: RawProjectionMatchKey): IntArray? = matches[key]

    @Synchronized
    fun put(key: RawProjectionMatchKey, nodeIds: IntArray) {
        if (matches.containsKey(key)) return
        while (matches.size >= MAX_RAW_PROJECTION_MATCH_ENTRIES) {
            matches.remove(matches.entries.iterator().next().key)
        }
        matches[key] = nodeIds
    }

    @Synchronized
    fun clear() = matches.clear()

    @Synchronized
    fun size(): Int = matches.size
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

private fun reusableContains(
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
