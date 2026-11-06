package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndex.PropertyCsr
import java.io.DataInput
import java.util.zip.CRC32

internal data class CallSiteIndexReadContext(
    val expectedStringCount: Int,
    val expectedCallSiteCount: Int,
    val expectedContentIdentity: ByteArray,
    val nodeOrder: (Int) -> Long,
    val nodeIdCapacity: Int,
    val rawStringPropertyId: (Int, Int) -> Int,
    val stringTable: StringTable
)

internal class CallSiteStringIndexReader(
    private val input: DataInput,
    private val context: CallSiteIndexReadContext,
    workConsumer: GraphWorkConsumer?
) {
    private val work = PersistentIndexReadWork(workConsumer)
    private var reservation: MappedCallSiteStringIndexMemoryBudget.Reservation? = null

    private data class Header(
        val stringCount: Int,
        val callSiteCount: Int,
        val identity: ByteArray,
        val uniqueCounts: IntArray,
        val trigramCount: Int,
        val retainedBytes: Long
    )

    fun read(): MappedCallSiteStringIndex? {
        return try {
            val header = readHeader()
            val retainedBytes = retainedBytes(header) ?: return null
            require(header.retainedBytes == retainedBytes)
            val retained = MappedCallSiteStringIndexMemoryBudget.tryReserve(retainedBytes)
                ?: throw MappedCallSiteStringIndexPersistenceBudgetDeniedException()
            reservation = retained
            val checksum = headerChecksum(header)
            val properties = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                readProperty(header, propertyIndex, checksum)
            }
            val signatures = LongArray(header.stringCount) { readLong().also(checksum::updateLong) }
            val postings = readTrigramPostings(header, checksum)
            require(readLong() == checksum.value)
            work.flush()
            val loaded = createIndex(properties, signatures, postings, retained)
            reservation = null
            loaded
        } finally {
            try { reservation?.close() } finally { work.flush() }
        }
    }

    private fun readInt(): Int {
        work.consume()
        return input.readInt()
    }

    private fun readLong(): Long {
        work.consume()
        return input.readLong()
    }

    private fun readHeader(): Header {
        require(readInt() == CALL_SITE_STRING_INDEX_MAGIC)
        require(readInt() == CALL_SITE_STRING_INDEX_VERSION)
        val stringCount = readInt()
        val callSiteCount = readInt()
        require(stringCount == context.expectedStringCount)
        require(callSiteCount == context.expectedCallSiteCount)
        require(context.expectedContentIdentity.size == CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
        val identity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
        input.readFully(identity)
        repeat(identity.size) { work.consume() }
        require(identity.contentEquals(context.expectedContentIdentity))
        val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { readInt() }
        require(uniqueCounts.all { count -> count in 0..stringCount })
        val trigramCount = readInt()
        require(trigramCount > 0)
        return Header(stringCount, callSiteCount, identity, uniqueCounts, trigramCount, readLong())
    }

    private fun retainedBytes(header: Header): Long? {
        val base = estimatedMappedCallSiteStringIndexRetainedBytes(
            header.callSiteCount.toLong(), header.stringCount, header.uniqueCounts
        ) ?: return null
        return try {
            Math.addExact(base, Math.addExact(
                PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
                Math.multiplyExact(header.trigramCount.toLong(), Long.SIZE_BYTES.toLong())
            ))
        } catch (_: ArithmeticException) { null }
    }

    private fun headerChecksum(header: Header): CRC32 = CRC32().apply {
        updateInt(CALL_SITE_STRING_INDEX_MAGIC)
        updateInt(CALL_SITE_STRING_INDEX_VERSION)
        updateInt(header.stringCount)
        updateInt(header.callSiteCount)
        update(header.identity)
        header.uniqueCounts.forEach(::updateInt)
        updateInt(header.trigramCount)
        updateLong(header.retainedBytes)
    }

    private fun readProperty(header: Header, propertyIndex: Int, checksum: CRC32): PropertyCsr {
        val count = header.uniqueCounts[propertyIndex]
        val stringIds = readStringIds(count, header.stringCount, checksum)
        val ends = readPostingEnds(count, header.callSiteCount, checksum)
        val nodes = readPostingNodeIds(header.callSiteCount, ends, checksum)
        return PropertyCsr(ends, stringIds, nodes, prevalidated = true)
    }

    private fun readStringIds(count: Int, stringCount: Int, checksum: CRC32): IntArray {
        var previous = -1
        return IntArray(count) { readInt().also { stringId ->
            checksum.updateInt(stringId)
            require(stringId in 0 until stringCount && stringId > previous)
            previous = stringId
        } }
    }

    private fun readPostingEnds(count: Int, callSiteCount: Int, checksum: CRC32): IntArray {
        var previous = 0
        val ends = IntArray(count) { readInt().also { end ->
            checksum.updateInt(end)
            require(end in (previous + 1)..callSiteCount)
            previous = end
        } }
        require(previous == callSiteCount)
        return ends
    }

    private fun readPostingNodeIds(count: Int, ends: IntArray, checksum: CRC32): IntArray {
        var row = 0
        var previousOrder = Long.MIN_VALUE
        val nodes = IntArray(count) { position -> readInt().also { nodeId ->
            checksum.updateInt(nodeId)
            require(nodeId in 0 until context.nodeIdCapacity)
            val order = context.nodeOrder(nodeId)
            require(order > previousOrder)
            previousOrder = order
            if (position + 1 == ends[row]) {
                row++
                previousOrder = Long.MIN_VALUE
            }
        } }
        require(row == ends.size)
        return nodes
    }

    private fun readTrigramPostings(header: Header, checksum: CRC32): LongArray {
        var previous = Long.MIN_VALUE
        return LongArray(header.trigramCount) { readLong().also { posting ->
            checksum.updateLong(posting)
            require(posting >= previous)
            require(posting.toInt() in 0 until header.stringCount)
            previous = posting
        } }
    }

    private fun createIndex(
        properties: Array<PropertyCsr>,
        signatures: LongArray,
        postings: LongArray,
        retained: MappedCallSiteStringIndexMemoryBudget.Reservation
    ): MappedCallSiteStringIndex = MappedCallSiteStringIndex(
        properties,
        context.stringTable,
        context.nodeOrder,
        context.nodeIdCapacity,
        context.rawStringPropertyId,
        contentIdentity = { context.expectedContentIdentity.copyOf() },
        reservation = retained,
        prepareExactProjectionTupleIndex = true
    ).also { index ->
        signatures.copyInto(index.trigramSignatures)
        index.trigramMetadataInitialized = true
        index.trigramPostingCount = postings.size.toLong()
        index.trigramPostingsInitialized = true
        index.trigramPostings = postings
    }
}

internal fun callSiteIndexPersistentChecksum(
    stringCount: Int,
    callSiteCount: Int,
    contentIdentity: ByteArray,
    retainedBytes: Long,
    properties: Array<PropertyCsr>,
    signatures: LongArray,
    postings: LongArray
): Long = CRC32().also { checksum ->
    checksum.updateInt(CALL_SITE_STRING_INDEX_MAGIC)
    checksum.updateInt(CALL_SITE_STRING_INDEX_VERSION)
    checksum.updateInt(stringCount)
    checksum.updateInt(callSiteCount)
    checksum.update(contentIdentity)
    properties.forEach { property -> checksum.updateInt(property.uniqueStringCount) }
    checksum.updateInt(postings.size)
    checksum.updateLong(retainedBytes)
    properties.forEach { property -> property.updatePersistentChecksum(checksum) }
    signatures.forEachIndexed { index, value ->
        if ((index and CALL_SITE_INDEX_PERSISTENCE_POLL_MASK) == 0) {
            checkCallSiteIndexPersistenceInterrupted()
        }
        checksum.updateLong(value)
    }
    postings.forEachIndexed { index, value ->
        if ((index and CALL_SITE_INDEX_PERSISTENCE_POLL_MASK) == 0) {
            checkCallSiteIndexPersistenceInterrupted()
        }
        checksum.updateLong(value)
    }
}.value

internal fun callSiteIndexPersistentRetainedBytes(
    callSiteCount: Int,
    stringCount: Int,
    properties: Array<PropertyCsr>,
    postings: LongArray
): Long {
    val baseRetainedBytes = checkNotNull(
        estimatedMappedCallSiteStringIndexRetainedBytes(
            callSiteCount.toLong(),
            stringCount,
            IntArray(properties.size) { index -> properties[index].uniqueStringCount }
        )
    )
    return Math.addExact(
        baseRetainedBytes,
        Math.addExact(
            PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
            Math.multiplyExact(postings.size.toLong(), Long.SIZE_BYTES.toLong())
        )
    )
}
