package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.PreferredMappedStringIndexViewGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

internal fun MappedGraphContext.mappedCallSiteStringIndexView(workConsumer: GraphWorkConsumer?): MappedCallSiteStringIndexView? {
    if (workConsumer !is PreferredMappedStringIndexViewGraphWorkBatchConsumer ||
        !persistentCallSiteStringIndexEnabled ||
        !Files.isRegularFile(callSiteStringIndexFile))
        return null
    return mappedCallSiteStringIndexView
        ?: if (mappedCallSiteStringIndexViewUnavailable) null
        else
            synchronized(mappedCallSiteStringIndexViewLock) {
                mappedCallSiteStringIndexView?.let {
                    return@synchronized it
                }
                if (mappedCallSiteStringIndexViewUnavailable) return@synchronized null
                val callSiteCount = nodeTypeIndex.count(CallSiteNode::class.java)
                if (callSiteCount <= 0L || callSiteCount > Int.MAX_VALUE) {
                    mappedCallSiteStringIndexViewUnavailable = true
                    return@synchronized null
                }
                val loaded =
                    MappedCallSiteStringIndexView.load(
                        callSiteStringIndexFile,
                        CallSiteIndexViewContext(
                            stringTable.size(),
                            callSiteCount.toInt(),
                            persistedCallSiteStringIndexContentIdentity(workConsumer),
                            stringTable,
                            nodeOffsets.size,
                            nodeOrder = { nodeId -> nodeOffsets.offset(nodeId) }),
                        workConsumer = workConsumer)
                if (loaded == null) {
                    mappedCallSiteStringIndexViewUnavailable = true
                } else {
                    mappedCallSiteStringIndexView = loaded
                }
                loaded
            }
}

internal fun MappedGraphContext.close() {
    clearStringPropertyIndexes()
    // MappedByteBuffer is unmapped by GC; no explicit unmap in standard API
}

internal fun MappedGraphContext.clearStringPropertyIndexes() {
    closeCallSiteStringIndex(force = true)
    synchronized(stringPropertyIndexLock) {
        stringPropertyIndexes.clear()
        stringPropertyAdmissions.clear()
        rawStringMatchStates.clear()
        rawProjectionMatches.clear()
    }
}

internal fun MappedGraphContext.releaseStringPropertyDisjunctionCache() {
    closeCallSiteStringIndex(force = false)
    checkCallSiteIndexPersistenceInterrupted()
}

internal fun MappedGraphContext.closeCallSiteStringIndex(force: Boolean) {
    if (force)
        synchronized(mappedCallSiteStringIndexViewLock) {
            mappedCallSiteStringIndexView?.close()
            mappedCallSiteStringIndexView = null
            mappedCallSiteStringIndexViewUnavailable = false
        }
    synchronized(callSiteStringIndexLock) {
        // A retained persisted sidecar serves split waves; newly built postings defer persistence
        // until graph close while releasing only request-owned caches between queries.
        val index = callSiteStringIndex
        when {
            !force && callSiteStringIndexLoadedFromPersistence && retainPersistedCallSiteStringIndex.get() -> index?.clearQueryCaches()
            !force && !Thread.currentThread().isInterrupted && canPersistCallSiteIndex(index) -> index?.clearQueryCaches()
            force && canPersistCallSiteIndex(index) ->
                try {
                    persistPreparedCallSiteStringIndex()
                } finally {
                    resetCallSiteStringIndex(index)
                }
            else -> resetCallSiteStringIndex(index)
        }
    }
}

private fun MappedGraphContext.canPersistCallSiteIndex(index: MappedCallSiteStringIndex?): Boolean =
    persistentCallSiteStringIndexEnabled && !callSiteStringIndexLoadedFromPersistence && index?.isTrigramPostingsInitialized() == true

private fun MappedGraphContext.resetCallSiteStringIndex(index: MappedCallSiteStringIndex?) {
    index?.close()
    callSiteStringIndex = null
    callSiteStringIndexLoadedFromPersistence = false
    retainPersistedCallSiteStringIndex.set(false)
}

internal fun MappedGraphContext.retainPersistedCallSiteStringIndexForSplit(workConsumer: GraphWorkConsumer?) {
    if (workConsumer is SplitGraphWorkBatchConsumer && workConsumer !is PreferredMappedStringIndexViewGraphWorkBatchConsumer)
        retainPersistedCallSiteStringIndex.set(true)
}

/**
 * Prepares the complete CallSite string search path before the graph is exposed to queries. A denied shared-memory reservation is a normal
 * fallback and leaves raw scans available.
 */
internal fun MappedGraphContext.prepareCallSiteStringIndex(
    workConsumer: GraphWorkConsumer = CALL_SITE_INDEX_PREPARATION_WORK_CONSUMER
): Boolean {
    val index =
        if (!Files.isRegularFile(callSiteStringIndexFile) && prepareCallSiteStringIndexInParallel(workConsumer)) {
            callSiteStringIndex
        } else {
            callSiteStringIndex(CallSiteNode::class.java, workConsumer)
        } ?: return false
    return index.prepareTrigramPostings(workConsumer)
}

@Suppress("TooGenericExceptionCaught")
internal fun MappedGraphContext.persistPreparedCallSiteStringIndex(): Boolean =
    synchronized(callSiteStringIndexLock) {
        val index = callSiteStringIndex ?: return@synchronized false
        if (callSiteStringIndexLoadedFromPersistence && Files.isRegularFile(callSiteStringIndexFile)) return@synchronized true
        var temporary: Path? = null
        try {
            temporary = Files.createTempFile(callSiteStringIndexFile.parent, callSiteStringIndexFile.fileName.toString(), ".tmp")
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(temporary), CALL_SITE_INDEX_IO_BUFFER_BYTES))
                .use(index::writePersistent)
            checkCallSiteIndexPersistenceInterrupted()
            replaceAtomically(temporary, callSiteStringIndexFile)
            temporary = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            temporary?.let { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

internal fun MappedGraphContext.replaceAtomically(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun MappedGraphContext.callSiteStringIndex(
    type: Class<out Node>,
    workConsumer: GraphWorkConsumer? = null
): MappedCallSiteStringIndex? {
    if (type != CallSiteNode::class.java) return null
    return callSiteStringIndex
        ?: synchronized(callSiteStringIndexLock) {
            callSiteStringIndex?.let {
                return@synchronized it
            }
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
            val reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(countBytes) ?: return@synchronized null
            buildSerialCallSiteStringIndex(nodeCount, stringCount, reservation, workConsumer)
        }
}

@Suppress("TooGenericExceptionCaught")
private fun MappedGraphContext.buildSerialCallSiteStringIndex(
    nodeCount: Long,
    stringCount: Int,
    reservation: MappedCallSiteStringIndexMemoryBudget.Reservation,
    workConsumer: GraphWorkConsumer?
): MappedCallSiteStringIndex? {
    return try {
        val capacity = nodeCount.toInt()
        val endsByStringId = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(stringCount) }
        forEachRawCallSiteStringIds(workConsumer) { _, callerClass, callerName, calleeClass, calleeName ->
            endsByStringId[CALLER_CLASS_PROPERTY_INDEX][callerClass]++
            endsByStringId[CALLER_NAME_PROPERTY_INDEX][callerName]++
            endsByStringId[CALLEE_CLASS_PROPERTY_INDEX][calleeClass]++
            endsByStringId[CALLEE_NAME_PROPERTY_INDEX][calleeName]++
        }
        val uniqueCounts =
            IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> endsByStringId[propertyIndex].count { count -> count > 0 } }
        val retainedBytes =
            estimatedMappedCallSiteStringIndexRetainedBytes(nodeCount, stringCount, uniqueCounts)?.takeIf(reservation::tryGrowTo)
                ?: run {
                    reservation.close()
                    return null
                }
        val usedStringIds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> IntArray(uniqueCounts[propertyIndex]) }
        val postingEnds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> IntArray(uniqueCounts[propertyIndex]) }
        val postings = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(capacity) }
        endsByStringId.indices.forEach { propertyIndex ->
            val ends = endsByStringId[propertyIndex]
            val used = usedStringIds[propertyIndex]
            prepareSerialPostingOffsets(ends, used, capacity)
        }
        forEachRawCallSiteStringIds(workConsumer) { nodeId, callerClass, callerName, calleeClass, calleeName ->
            postings[CALLER_CLASS_PROPERTY_INDEX][endsByStringId[CALLER_CLASS_PROPERTY_INDEX][callerClass]++] = nodeId
            postings[CALLER_NAME_PROPERTY_INDEX][endsByStringId[CALLER_NAME_PROPERTY_INDEX][callerName]++] = nodeId
            postings[CALLEE_CLASS_PROPERTY_INDEX][endsByStringId[CALLEE_CLASS_PROPERTY_INDEX][calleeClass]++] = nodeId
            postings[CALLEE_NAME_PROPERTY_INDEX][endsByStringId[CALLEE_NAME_PROPERTY_INDEX][calleeName]++] = nodeId
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
                    MappedCallSiteStringIndex.PropertyCsr(postingEnds[propertyIndex], usedStringIds[propertyIndex], postings[propertyIndex])
                },
                stringTable,
                nodeOrder = { nodeId -> nodeOffsets.offset(nodeId) },
                nodeIdCapacity = nodeOffsets.size,
                rawStringPropertyId = ::rawCallSiteStringPropertyId,
                contentIdentity = { callSiteStringIndexContentIdentity(workConsumer = null) },
                reservation = reservation)
            .also { built ->
                callSiteStringIndex = built
                callSiteStringIndexLoadedFromPersistence = false
            }
    } catch (error: Throwable) {
        reservation.close()
        throw error
    }
}

private fun prepareSerialPostingOffsets(ends: IntArray, used: IntArray, capacity: Int) {
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

/**
 * Restores a production-built sidecar for a cross-graph scan without falling through to the serial in-memory builder. A missing, corrupt,
 * or budget-denied sidecar deliberately returns null so the caller can use the additive graph/segment raw-scan fallback.
 */
internal fun MappedGraphContext.loadPersistedCallSiteStringIndexIfAvailable(
    type: Class<out Node>,
    workConsumer: GraphWorkConsumer
): MappedCallSiteStringIndex? {
    if (type != CallSiteNode::class.java || !persistentCallSiteStringIndexEnabled || !Files.isRegularFile(callSiteStringIndexFile)) {
        return null
    }
    return callSiteStringIndex
        ?: synchronized(callSiteStringIndexLock) {
            callSiteStringIndex?.let {
                return@synchronized it
            }
            val nodeCount = nodeTypeIndex.count(CallSiteNode::class.java)
            if (nodeCount <= 0L || nodeCount > Int.MAX_VALUE) return@synchronized null
            loadPersistedCallSiteStringIndex(nodeCount.toInt(), workConsumer)?.also { persisted ->
                callSiteStringIndex = persisted
                callSiteStringIndexLoadedFromPersistence = true
            }
        }
}

@Suppress("SwallowedException", "ThrowsCount", "TooGenericExceptionCaught")
internal fun MappedGraphContext.loadPersistedCallSiteStringIndex(
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
        DataInputStream(BufferedInputStream(Files.newInputStream(callSiteStringIndexFile), CALL_SITE_INDEX_IO_BUFFER_BYTES)).use { input ->
            val loaded =
                MappedCallSiteStringIndex.readPersistent(
                    input,
                    CallSiteIndexReadContext(
                        stringTable.size(),
                        callSiteCount,
                        contentIdentity,
                        nodeOrder = { nodeId -> nodeOffsets.offset(nodeId) },
                        nodeIdCapacity = nodeOffsets.size,
                        rawStringPropertyId = ::rawCallSiteStringPropertyId,
                        stringTable = stringTable),
                    workConsumer = workConsumer)
            if (loaded != null) validateIndexEnd(input, loaded, workConsumer)
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

@Suppress("TooGenericExceptionCaught")
private fun validateIndexEnd(input: DataInputStream, loaded: MappedCallSiteStringIndex, workConsumer: GraphWorkConsumer?) {
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

internal fun MappedGraphContext.persistedCallSiteStringIndexContentIdentity(workConsumer: GraphWorkConsumer?): ByteArray {
    val persisted =
        runCatching { Files.readAllBytes(callSiteStringIndexIdentityFile) }
            .getOrNull()
            ?.takeIf { identity -> identity.size == CALL_SITE_STRING_INDEX_IDENTITY_BYTES }
    if (persisted != null) {
        consumeGraphWork(workConsumer, 1L)
        return persisted
    }
    return callSiteStringIndexContentIdentity(workConsumer)
}
