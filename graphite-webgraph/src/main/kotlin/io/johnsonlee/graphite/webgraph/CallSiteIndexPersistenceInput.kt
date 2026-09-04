package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val CALL_SITE_INDEX_PERSISTENCE_BUFFER_BYTES = 1 shl 20

@Suppress("ReturnCount", "TooGenericExceptionCaught")
internal fun persistCallSiteStringIndex(
    input: CallSiteIndexPersistenceInput,
    stringTable: StringTable,
    dir: Path
): Boolean {
    var temporary: Path? = null
    return try {
        val identity = Files.readAllBytes(dir.resolve(CALL_SITE_STRING_CONTENT_IDENTITY_FILE))
        val index = input.build(stringTable, identity) ?: return false
        index.use {
            if (!index.prepareTrigramPostings()) return false
            temporary = Files.createTempFile(dir, GraphStore.CALL_SITE_STRING_INDEX_FILE, ".tmp")
            DataOutputStream(
                BufferedOutputStream(
                    Files.newOutputStream(checkNotNull(temporary)),
                    CALL_SITE_INDEX_PERSISTENCE_BUFFER_BYTES
                )
            ).use(index::writePersistent)
            replaceCallSiteIndex(checkNotNull(temporary), dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE))
            temporary = null
        }
        true
    } catch (_: Exception) {
        false
    } finally {
        temporary?.let { path -> runCatching { Files.deleteIfExists(path) } }
    }
}

private fun replaceCallSiteIndex(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Captures the already-streamed CallSite fields needed to persist the query index without a second graph scan. */
internal class CallSiteIndexPersistenceInput(callSiteCount: Int) {
    private val nodeIds = IntArray(callSiteCount)
    private val propertyStringIds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(callSiteCount) }
    private var size = 0

    fun add(node: CallSiteNode, stringTable: StringTable) {
        val index = size++
        nodeIds[index] = node.id.value
        propertyStringIds[CALLER_CLASS_PROPERTY_INDEX][index] =
            stringTable.indexOf(node.caller.declaringClass.className)
        propertyStringIds[CALLER_NAME_PROPERTY_INDEX][index] = stringTable.indexOf(node.caller.name)
        propertyStringIds[CALLEE_CLASS_PROPERTY_INDEX][index] =
            stringTable.indexOf(node.callee.declaringClass.className)
        propertyStringIds[CALLEE_NAME_PROPERTY_INDEX][index] = stringTable.indexOf(node.callee.name)
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun build(stringTable: StringTable, contentIdentity: ByteArray): MappedCallSiteStringIndex? {
        check(size == nodeIds.size)
        val reservation = estimatedMappedCallSiteStringIndexCountBytes(stringTable.size())
            ?.let(MappedCallSiteStringIndexMemoryBudget::tryReserve)
            ?: return null
        try {
            val properties = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                buildProperty(propertyIndex, stringTable.size())
            }
            val retainedBytes = estimatedMappedCallSiteStringIndexRetainedBytes(
                size.toLong(),
                stringTable.size(),
                IntArray(properties.size) { index -> properties[index].uniqueStringCount }
            )
            if (retainedBytes == null || !reservation.tryGrowTo(retainedBytes)) {
                reservation.close()
                return null
            }
            reservation.shrinkTo(retainedBytes)
            return MappedCallSiteStringIndex(
                properties,
                stringTable,
                nodeOrder = { error("Persistence-only CallSite index has no query node order") },
                nodeIdCapacity = (nodeIds.maxOrNull() ?: -1) + 1,
                rawStringPropertyId = { _, _ ->
                    error("Persistence-only CallSite index has no raw property accessor")
                },
                contentIdentity = { contentIdentity.copyOf() },
                reservation = reservation
            )
        } catch (error: Throwable) {
            reservation.close()
            throw error
        }
    }

    private fun buildProperty(propertyIndex: Int, stringCount: Int): MappedCallSiteStringIndex.PropertyCsr {
        val counts = IntArray(stringCount)
        propertyStringIds[propertyIndex].forEach { stringId -> counts[stringId]++ }
        val usedStringIds = IntArray(counts.count { count -> count > 0 })
        val postingEnds = IntArray(usedStringIds.size)
        var usedIndex = 0
        var postingOffset = 0
        counts.indices.forEach { stringId ->
            val count = counts[stringId]
            if (count == 0) return@forEach
            usedStringIds[usedIndex++] = stringId
            counts[stringId] = postingOffset
            postingOffset += count
        }
        val postings = IntArray(size)
        repeat(size) { index ->
            val stringId = propertyStringIds[propertyIndex][index]
            postings[counts[stringId]++] = nodeIds[index]
        }
        postingEnds.indices.forEach { row -> postingEnds[row] = counts[usedStringIds[row]] }
        return MappedCallSiteStringIndex.PropertyCsr(postingEnds, usedStringIds, postings)
    }
}
