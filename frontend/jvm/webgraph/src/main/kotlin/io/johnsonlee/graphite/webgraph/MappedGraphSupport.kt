package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.lang.MutableString
import java.io.DataInput
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal val callSiteScanWorkerNumber = AtomicInteger()
internal val callSiteScanParallelism: Int by lazy {
    val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    System.getProperty(CALL_SITE_SCAN_PARALLELISM_PROPERTY)?.toIntOrNull()?.coerceIn(1, processors) ?: processors
}
internal val callSiteScanExecutor by lazy {
    Executors.newFixedThreadPool(callSiteScanParallelism) { runnable ->
        Thread(runnable, "graphite-callsite-scan-${callSiteScanWorkerNumber.incrementAndGet()}").apply { isDaemon = true }
    }
}

internal data class ParallelCallSiteScanResult(
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

internal data class ParallelCallSiteProjectionResult(val workerIndex: Int, val rows: List<StringPropertyDistinctRow>)

internal const val NODE_ID_SCAN_CANCELLATION_MASK = 255
internal const val NODE_HEADER_BYTES = Int.SIZE_BYTES + Byte.SIZE_BYTES
internal const val METHOD_DESCRIPTOR_FIXED_INTS = 4

/** Owner, name and count precede a method descriptor record's parameter type IDs. */
internal const val METHOD_PARAMETERS_OFFSET_INTS = 3
internal const val GRAPH_ID_PROJECTION_PROPERTY = "graphId"
internal val CALL_SITE_NULL_STRING_PROPERTIES = setOf("class", "name")
internal const val ASCII_MAX_CODE = 0x7f
internal const val MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED = "Mapped string-property scan interrupted"
internal const val CALL_SITE_SCAN_PARALLELISM_PROPERTY = "graphite.webgraph.callSiteScanParallelism"
internal val CALL_SITE_INDEX_PREPARATION_WORK_CONSUMER = ParallelGraphWorkBatchConsumer { _ -> }
internal const val MIN_PARALLEL_CALL_SITE_SCAN_NODES = 4_096L
internal const val CALL_SITE_INDEX_IO_BUFFER_BYTES = 1 shl 20
internal const val CALL_SITE_STRING_CONTENT_IDENTITY_FILE = "graph.callsite-string-content.identity"
internal const val CALL_SITE_STRING_INDEX_IDENTITY_BYTES = 32
internal const val MAX_STRING_PROPERTY_INDEXES = 4
internal const val MIN_CALL_SITE_STRING_PREFLIGHT_NODES = 4_096L
internal const val MIN_CALL_SITE_STRING_PREFLIGHT_TERM_LENGTH = 16
internal const val MAX_STRING_PROPERTY_ADMISSION_NODES = 256
internal const val MAX_STRING_PROPERTY_ADMISSIONS = 32
internal const val MAX_STRING_PROPERTY_ADMISSION_BYTES = 64L * 1024
internal const val STRING_PROPERTY_ADMISSION_ESTIMATED_BYTES = 96L
internal const val MAX_STRING_PROPERTY_INDEX_RETAINED_BYTES = 8L * 1024 * 1024
internal const val MAX_RAW_STRING_MATCH_STATE_BYTES = 16 * 1024 * 1024
internal const val MAX_RAW_STRING_MATCH_STATES = 32
internal const val LOCAL_STRING_MATCH_CACHE_CAPACITY = 1 shl 16
internal const val LOCAL_STRING_MATCH_CACHE_HASH_SHIFT = 16
internal const val RAW_PROJECTION_STRING_MATCH_CACHE_CAPACITY = 1 shl 12
internal const val RAW_PROJECTION_MIN_PROBE_NODES = 64
internal const val RAW_PROJECTION_MAX_PROBE_NODES = 1_024
internal const val RAW_PROJECTION_PROBE_FACTOR = 4
internal const val MAX_RAW_PROJECTION_MATCH_ENTRIES = 16
internal const val ORDERED_PREFIX_WAVE_COUNT = 2
internal const val RAW_STRING_MATCH_STATE_ENTRY_ESTIMATED_BYTES = 96L
internal const val STRING_PROPERTY_INDEX_ARRAYS = 3
internal const val PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES = 16L
internal const val MAX_STRING_MATCH_CACHE_ENTRIES = 32
internal const val MAX_STRING_MATCH_CACHE_BYTES = 2L * 1024 * 1024
internal const val STRING_MATCH_CACHE_ENTRY_ESTIMATED_BYTES = 64L
internal const val STRING_HEADER_ESTIMATED_BYTES = 24L
internal const val MAX_TRIGRAM_INDEX_RETAINED_BYTES = 16L * 1024 * 1024
internal const val MAX_TRIGRAM_POSTINGS = 1_000_000
internal const val MAX_TRIGRAM_INDEX_STRINGS = 500_000
internal const val TRIGRAM_ENTRY_ESTIMATED_BYTES = 64L
internal const val TRIGRAM_POSTING_ESTIMATED_BYTES = 12L
internal const val MIN_TRIGRAM_LENGTH = 3
internal const val STRING_PROPERTY_INDEX_LOAD_FACTOR = 0.75f
private const val RAW_STRING_HASH_FACTOR = 31
internal const val RAW_STRING_MISS: Byte = 1
internal const val RAW_STRING_MATCH: Byte = 2
internal const val RAW_SCAN_INTERRUPTION_POLL_MASK = 1_023
internal const val GRAPH_WORK_ACCOUNTING_BATCH_SIZE = 1_024L

internal fun List<StringPropertyPredicate>.shareStringMatcher(): Boolean =
    firstOrNull()?.let { first ->
        drop(1).all { predicate ->
            predicate.transform == first.transform && predicate.mode == first.mode && predicate.expected == first.expected
        }
    } == true

internal fun estimatedStringPropertyIndexBytes(nodeCount: Long): Long =
    STRING_PROPERTY_INDEX_ARRAYS * PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES + nodeCount * STRING_PROPERTY_INDEX_ARRAYS * Int.SIZE_BYTES

internal data class StringPropertyKey(val type: Class<out Node>, val property: String)

internal data class StringPropertyAdmissionKey(
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

internal data class StringPredicateKey(val transform: StringValueTransform?, val mode: StringMatchMode, val expected: String)

internal data class RawProjectionMatchKey(val predicates: List<StringPropertyPredicate>, val limit: Int)

internal class RawProjectionMatches {
    private val matches =
        LinkedHashMap<RawProjectionMatchKey, IntArray>(MAX_RAW_PROJECTION_MATCH_ENTRIES + 1, STRING_PROPERTY_INDEX_LOAD_FACTOR, true)

    @Synchronized operator fun get(key: RawProjectionMatchKey): IntArray? = matches[key]

    @Synchronized
    fun put(key: RawProjectionMatchKey, nodeIds: IntArray) {
        if (matches.containsKey(key)) return
        while (matches.size >= MAX_RAW_PROJECTION_MATCH_ENTRIES) {
            matches.remove(matches.entries.iterator().next().key)
        }
        matches[key] = nodeIds
    }

    @Synchronized fun clear() = matches.clear()

    @Synchronized fun size(): Int = matches.size
}

/**
 * Serial, allocation-bounded predicate state for scans over a large global string table. A cache collision only repeats the deterministic
 * comparison and cannot change its result.
 */
internal class BoundedStringMatcher(
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

    fun matches(stringId: Int): Boolean =
        when (state(stringId)) {
            RAW_STRING_MATCH -> true
            RAW_STRING_MISS -> false
            else -> matchUncached(stringId)
        }

    private fun matchUncached(stringId: Int): Boolean {
        stringTable.get(stringId, actual)
        val matched =
            if (predicate.mode == StringMatchMode.CONTAINS) {
                reusableContains(actual, predicate.transform, predicate.expected)
            } else {
                stringMatches(actual.toString(), predicate.transform, predicate.mode, predicate.expected)
            }
        put(stringId, if (matched) RAW_STRING_MATCH else RAW_STRING_MISS)
        return matched
    }

    private fun state(stringId: Int): Byte {
        dense?.let {
            return it[stringId]
        }
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
    private val states =
        LinkedHashMap<RawStringMatchKey, ByteArray>(maxEntries.coerceAtLeast(1) + 1, STRING_PROPERTY_INDEX_LOAD_FACTOR, true)
    private var bytes = 0L

    @Synchronized
    fun stateFor(key: RawStringMatchKey, stringCount: Int): ByteArray? {
        var state = states[key]
        val requiredBytes = estimatedRawStringMatchStateBytes(key, stringCount)
        if (state == null && maxEntries > 0 && requiredBytes <= maxRetainedBytes) {
            while (states.isNotEmpty() && (states.size >= maxEntries || requiredBytes > maxRetainedBytes - bytes)) {
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
    fun contains(type: Class<out Node>, predicate: StringPropertyPredicate): Boolean =
        states.keys.any { key ->
            key.property.type == type &&
                key.transform == predicate.transform &&
                key.mode == predicate.mode &&
                key.expected == predicate.expected
        }

    @Synchronized
    fun clear() {
        states.clear()
        bytes = 0L
    }

    @Synchronized fun retainedBytes(): Long = bytes

    @Synchronized fun size(): Int = states.size
}

internal fun estimatedRawStringMatchStateBytes(key: RawStringMatchKey, stringCount: Int): Long =
    RAW_STRING_MATCH_STATE_ENTRY_ESTIMATED_BYTES +
        PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES +
        STRING_HEADER_ESTIMATED_BYTES +
        key.property.property.length.toLong() * Char.SIZE_BYTES +
        STRING_HEADER_ESTIMATED_BYTES +
        key.expected.length.toLong() * Char.SIZE_BYTES +
        stringCount.toLong()

internal class StringPropertyAdmissions {
    private val predicates =
        LinkedHashMap<StringPropertyAdmissionKey, Unit>(MAX_STRING_PROPERTY_ADMISSIONS + 1, STRING_PROPERTY_INDEX_LOAD_FACTOR, true)
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
        while (predicates.size > MAX_STRING_PROPERTY_ADMISSIONS || retainedBytes > MAX_STRING_PROPERTY_ADMISSION_BYTES) {
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

internal data class StringMatchKey(val transform: StringValueTransform?, val mode: StringMatchMode, val expected: String)

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
    private val matchingStrings =
        LinkedHashMap<StringMatchKey, IntArray>(MAX_STRING_MATCH_CACHE_ENTRIES + 1, STRING_PROPERTY_INDEX_LOAD_FACTOR, true)
    private var matchingStringCacheBytes = 0L

    fun matchingNodeIds(mode: StringMatchMode, expected: String): Sequence<Int> =
        matchingNodeIds(null, mode, expected, workConsumer = null, limit = Int.MAX_VALUE)

    fun matchingNodeIds(
        transform: StringValueTransform?,
        mode: StringMatchMode,
        expected: String,
        workConsumer: GraphWorkConsumer?,
        limit: Int = Int.MAX_VALUE
    ): Sequence<Int> {
        if (limit <= 0) return emptySequence()
        val matchedStrings = matchingStringIds(transform, mode, expected, workConsumer)
        return if (matchedStrings.isEmpty()) emptySequence()
        else
            sequence {
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
        matchingStrings[key]?.let {
            return it
        }

        val result =
            when {
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
        while (matchingStrings.size > maxMatchingStringCacheEntries || matchingStringCacheBytes > maxMatchingStringCacheBytes) {
            val entries = matchingStrings.entries.iterator()
            val eldest = entries.next()
            matchingStringCacheBytes -= estimatedMatchingStringCacheBytes(eldest.key, eldest.value)
            entries.remove()
        }
    }

    @Synchronized internal fun matchingStringCacheSize(): Int = matchingStrings.size

    private fun matchingContainsStringIds(expected: String, workConsumer: GraphWorkConsumer?): IntArray {
        val index = getTrigramIndex(workConsumer) ?: return scanMatchingStringIds(null, StringMatchMode.CONTAINS, expected, workConsumer)
        return shortestTrigramPosting(index, expected)?.let { shortestPosting ->
            matchingContainsFromPosting(shortestPosting, expected, workConsumer)
        } ?: IntArray(0)
    }

    private fun shortestTrigramPosting(index: Int2ObjectOpenHashMap<IntArray>, expected: String): IntArray? {
        var candidates: IntArray? = null
        val seenTrigrams = IntOpenHashSet()
        for (position in 0..expected.length - MIN_TRIGRAM_LENGTH) {
            val trigram = trigramHash(expected, position)
            if (!seenTrigrams.add(trigram)) continue
            val posting = index[trigram] ?: return null
            if (candidates == null || posting.size < candidates.size) candidates = posting
        }
        return candidates
    }

    private fun matchingContainsFromPosting(shortestPosting: IntArray, expected: String, workConsumer: GraphWorkConsumer?): IntArray {
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
                val matches =
                    when (mode) {
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

    private fun populateTrigramIndex(builder: TrigramIndexBuilder, workConsumer: GraphWorkConsumer?): Boolean {
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

internal class TrigramIndexBuilder(private val maxPostings: Int, private val maxBytes: Long) {
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
            val addedBytes = TRIGRAM_POSTING_ESTIMATED_BYTES + if (posting == null) TRIGRAM_ENTRY_ESTIMATED_BYTES else 0L
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
        builders.int2ObjectEntrySet().forEach { entry -> result.put(entry.intKey, entry.value.toIntArray()) }
        return result
    }
}

internal fun estimatedMatchingStringCacheBytes(key: StringMatchKey, strings: IntArray): Long =
    STRING_MATCH_CACHE_ENTRY_ESTIMATED_BYTES +
        PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES +
        STRING_HEADER_ESTIMATED_BYTES +
        key.expected.length.toLong() * Char.SIZE_BYTES +
        strings.size.toLong() * Int.SIZE_BYTES

internal fun estimatedStringPropertyAdmissionBytes(key: StringPropertyAdmissionKey): Long =
    STRING_PROPERTY_ADMISSION_ESTIMATED_BYTES +
        key.property.property.length.toLong() * Char.SIZE_BYTES +
        key.expected.length.toLong() * Char.SIZE_BYTES

internal fun stringMatches(actual: String, transform: StringValueTransform?, mode: StringMatchMode, expected: String): Boolean {
    val transformed = transformString(actual, transform)
    return when (mode) {
        StringMatchMode.EQUALS -> transformed == expected
        StringMatchMode.STARTS_WITH -> transformed.startsWith(expected)
        StringMatchMode.ENDS_WITH -> transformed.endsWith(expected)
        StringMatchMode.CONTAINS -> transformed.contains(expected)
    }
}

internal fun reusableContains(actual: MutableString, transform: StringValueTransform?, expected: String): Boolean {
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

internal fun transformString(value: String, transform: StringValueTransform?): String =
    when (transform) {
        null -> value
        StringValueTransform.LOWERCASE -> value.lowercase()
    }

internal fun trigramHash(value: String, position: Int): Int =
    (value[position].code * RAW_STRING_HASH_FACTOR + value[position + 1].code) * RAW_STRING_HASH_FACTOR + value[position + 2].code

internal fun supportsRawStringProperty(type: Class<out Node>, property: String): Boolean =
    RAW_STRING_PROPERTY_LAYOUT[type]?.contains(property) == true

private val RAW_STRING_PROPERTY_LAYOUT =
    mapOf<Class<out Node>, Set<String>>(
        StringConstant::class.java to setOf("value"),
        EnumConstant::class.java to setOf("enum_type", "name"),
        LocalVariable::class.java to setOf("name", "type"),
        FieldNode::class.java to setOf("class", "name", "type"),
        ParameterNode::class.java to setOf("type"),
        ResourceFileNode::class.java to setOf("path", "source", "format"),
        CallSiteNode::class.java to setOf("caller_class", "caller_name", "callee_class", "callee_name"))

/** Adapts a [ByteBuffer] as [DataInput] for node deserialization. No system calls -- reads directly from mapped memory. */
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

    override fun readLine(): String = throw UnsupportedOperationException("ByteBufferDataInput does not support readLine")

    override fun readUTF(): String = DataInputStream.readUTF(this)

    private fun requireRemaining(bytes: Int) {
        if (position < 0 || position > buf.limit() - bytes) throw EOFException()
    }

    private companion object {
        private const val USHORT_MASK = 0xFFFF
    }
}

internal fun MessageDigest.updateIdentityInt(value: Int) {
    for (byteIndex in Int.SIZE_BYTES - 1 downTo 0) {
        update((value ushr (byteIndex * Byte.SIZE_BITS)).toByte())
    }
}

internal fun MessageDigest.updateIdentityLong(value: Long) {
    for (byteIndex in Long.SIZE_BYTES - 1 downTo 0) {
        update((value ushr (byteIndex * Byte.SIZE_BITS)).toByte())
    }
}
