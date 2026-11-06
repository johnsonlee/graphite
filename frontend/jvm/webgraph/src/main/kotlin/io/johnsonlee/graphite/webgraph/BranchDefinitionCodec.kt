package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchScope
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.io.DataInput
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Encode the sidecar for [branchScopes] (their per-side definitions, aligned by position with the branch scopes in `graph.metadata`)
 * and [localDefinitions] (every write of each tracked local, keyed by local node id):
 * ```
 * int32    header         = NodeSerializer.MAGIC_BRANCHDEFS | NodeSerializer.BRANCH_DEFINITIONS_VERSION
 * int32    payloadLength
 * byte[32] payloadDigest  // SHA-256 of the payload; graph.metadata's trailer repeats it
 * payload:
 *   int32 scopeCount
 *   repeat scopeCount:
 *     int32 trueCount,  int32 x 3 x trueCount   // stmtOrdinal, localNodeId, constantNodeId | -1
 *     int32 falseCount, int32 x 3 x falseCount
 *   int32 localCount
 *   repeat localCount:
 *     int32 localNodeId, int32 count, int32 x 3 x count
 * ```
 *
 * The returned [EncodedBranchDefinitions.payloadDigest] is what [writeBranchDefinitionMetadataTrailer] stores in `graph.metadata`,
 * binding the sidecar
 * to the exact metadata written with it.
 */
internal fun encodeBranchDefinitions(branchScopes: List<BranchScopeData>, localDefinitions: Map<Int, IntArray>): EncodedBranchDefinitions {
    var payloadInts = 1L + 1L
    for (scope in branchScopes) payloadInts += 2L + scope.trueDefinitions.size + scope.falseDefinitions.size
    for (definitions in localDefinitions.values) payloadInts += 2L + definitions.size
    val payloadLength = payloadInts * Int.SIZE_BYTES
    require(payloadLength <= NodeSerializer.MAX_BRANCH_DEFINITIONS_PAYLOAD_BYTES) { "Branch definitions exceed the payload budget" }
    val buffer = ByteBuffer.allocate(NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES + payloadLength.toInt())
    buffer.position(NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES)
    buffer.putInt(branchScopes.size)
    for (scope in branchScopes) {
        putPackedDefinitions(buffer, scope.trueDefinitions)
        putPackedDefinitions(buffer, scope.falseDefinitions)
    }
    buffer.putInt(localDefinitions.size)
    for (localId in localDefinitions.keys.sorted()) {
        buffer.putInt(localId)
        putPackedDefinitions(buffer, localDefinitions.getValue(localId))
    }
    check(!buffer.hasRemaining()) { "Branch definition payload size mismatch" }
    val bytes = buffer.array()
    val payloadDigest = sha256(bytes, NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES, payloadLength.toInt())
    buffer.position(0)
    buffer.putInt(NodeSerializer.MAGIC_BRANCHDEFS or NodeSerializer.BRANCH_DEFINITIONS_VERSION)
    buffer.putInt(payloadLength.toInt())
    buffer.put(payloadDigest)
    return EncodedBranchDefinitions(bytes, payloadDigest)
}

private fun putPackedDefinitions(buffer: ByteBuffer, packed: IntArray) {
    buffer.putInt(packed.size / BranchScope.DEFINITION_STRIDE)
    for (value in packed) buffer.putInt(value)
}

/** Append the sidecar binding to `graph.metadata`: readers that predate it stop before the trailer. */
internal fun writeBranchDefinitionMetadataTrailer(dos: DataOutputStream, payloadDigest: ByteArray) {
    require(payloadDigest.size == NodeSerializer.DIGEST_BYTES) { "Expected a SHA-256 digest, got ${payloadDigest.size} bytes" }
    dos.writeInt(NodeSerializer.MAGIC_METADATA_TRAILER or NodeSerializer.BRANCH_DEFINITIONS_VERSION)
    dos.write(payloadDigest)
}

/**
 * Read the trailer that [writeBranchDefinitionMetadataTrailer] appends after the metadata sections, or `null` when the file ends
 * there (written before
 * the sidecar existed, or re-saved by such a writer).
 */
internal fun readBranchDefinitionMetadataTrailer(dis: DataInput): ByteArray? =
    try {
        val header = dis.readInt()
        if (header and HEADER_MAGIC_MASK == NodeSerializer.MAGIC_METADATA_TRAILER &&
            header and BYTE_MASK == NodeSerializer.BRANCH_DEFINITIONS_VERSION) {
            ByteArray(NodeSerializer.DIGEST_BYTES).also(dis::readFully)
        } else {
            null
        }
    } catch (_: EOFException) {
        null
    }

/**
 * Decode a sidecar's preamble: the header must match, the declared payload length must equal [payloadBytesInFile] and stay within
 * [maxPayloadBytes], and the payload digest must be the one `graph.metadata` binds to ([expectedPayloadDigest]). Nothing beyond the
 * preamble has been read when this returns, so a corrupt file never drives an allocation.
 */
internal fun decodePersistedBranchDefinitionPreamble(
    preamble: ByteArray,
    payloadBytesInFile: Long,
    expectedPayloadDigest: ByteArray?,
    maxPayloadBytes: Int = NodeSerializer.MAX_BRANCH_DEFINITIONS_PAYLOAD_BYTES
): BranchDefinitionPreamble {
    if (preamble.size < NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES) {
        return BranchDefinitionPreamble.rejected("is corrupt (too short)")
    }
    val buffer = ByteBuffer.wrap(preamble)
    val header = buffer.getInt()
    val payloadLength = buffer.getInt()
    val payloadDigest = ByteArray(NodeSerializer.DIGEST_BYTES).also(buffer::get)
    val rejection =
        when {
            header and HEADER_MAGIC_MASK != NodeSerializer.MAGIC_BRANCHDEFS ||
                header and BYTE_MASK != NodeSerializer.BRANCH_DEFINITIONS_VERSION ->
                "does not match (unknown header 0x${header.toString(NodeSerializer.HEX_RADIX)})"
            payloadLength < 0 || payloadLength > maxPayloadBytes ->
                "is corrupt (declared payload $payloadLength bytes exceeds the $maxPayloadBytes-byte budget)"
            payloadLength.toLong() != payloadBytesInFile ->
                "is corrupt (declared payload $payloadLength bytes, file has $payloadBytesInFile)"
            expectedPayloadDigest == null -> "does not match (graph.metadata carries no branch-definition digest)"
            !payloadDigest.contentEquals(expectedPayloadDigest) -> "does not match the definitions graph.metadata was written with"
            else -> null
        }
    return if (rejection == null) {
        BranchDefinitionPreamble(payloadLength, payloadDigest, null)
    } else {
        BranchDefinitionPreamble.rejected(rejection)
    }
}

/** Resolves a node id to the persisted [tagOf] tag of that node, or [NodeSerializer.NO_NODE] when the graph has no such node. */
internal fun interface BranchDefinitionNodeTags {
    fun tagOf(nodeId: Int): Int
}

/**
 * Decode a payload whose length and digest [decodePersistedBranchDefinitionPreamble] accepted. The bytes must hash to
 * [expectedPayloadDigest],
 * the scope count must equal [expectedScopeCount], every count is checked against the bytes that remain before an array is allocated,
 * the local table may hold at most [nodeCount] distinct locals (so its decoded size is bounded by the graph, not by the payload), and
 * the payload must end exactly after the local tables. The content is then held to the writer's invariants against the persisted nodes
 * ([nodeTags]): every table key is a `LocalVariable` node, every table entry names its key with strictly increasing ordinals, every
 * constant id is [BranchScope.NO_CONSTANT] or a constant node, every side definition appears in the table of its local, and every table
 * belongs to a local some side defines. A loaded graph therefore never exposes a definition that points outside it or a table that
 * disagrees with the side definitions a consumer subtracts from it.
 */
internal fun decodePersistedBranchDefinitionPayload(
    payload: ByteArray,
    expectedScopeCount: Int,
    expectedPayloadDigest: ByteArray,
    nodeCount: Int,
    nodeTags: BranchDefinitionNodeTags
): DecodedBranchDefinitions {
    if (!sha256(payload, 0, payload.size).contentEquals(expectedPayloadDigest)) {
        return DecodedBranchDefinitions.rejected("is corrupt (payload digest mismatch)")
    }
    return try {
        decodePayloadFields(ByteBuffer.wrap(payload), expectedScopeCount, nodeCount, DefinitionValidator(nodeTags))
    } catch (e: IllegalArgumentException) {
        DecodedBranchDefinitions.rejected("is corrupt (${e.message})")
    } catch (_: BufferUnderflowException) {
        DecodedBranchDefinitions.rejected("is corrupt (payload ends inside a field)")
    }
}

private fun sha256(bytes: ByteArray, offset: Int, length: Int): ByteArray =
    MessageDigest.getInstance(NodeSerializer.DIGEST_ALGORITHM).also { it.update(bytes, offset, length) }.digest()

private fun decodePayloadFields(
    buffer: ByteBuffer,
    expectedScopeCount: Int,
    nodeCount: Int,
    validator: DefinitionValidator
): DecodedBranchDefinitions {
    val scopeCount = buffer.getInt()
    if (scopeCount != expectedScopeCount) {
        return DecodedBranchDefinitions.rejected("does not match ($scopeCount branch scopes, metadata has $expectedScopeCount)")
    }
    val scopes = ArrayList<Pair<IntArray, IntArray>>(scopeCount)
    repeat(scopeCount) { scopes += validator.readSide(buffer) to validator.readSide(buffer) }
    val localCount = buffer.getInt()
    val maxLocals = minOf(nodeCount, buffer.remaining() / (2 * Int.SIZE_BYTES))
    require(localCount >= 0 && localCount <= maxLocals) { "invalid local count $localCount" }
    val locals = Int2ObjectOpenHashMap<IntArray>(localCount)
    repeat(localCount) {
        val localId = buffer.getInt()
        require(validator.isLocal(localId)) { "invalid local $localId" }
        require(locals.put(localId, validator.readTable(buffer, localId)) == null) { "duplicate local $localId" }
    }
    require(!buffer.hasRemaining()) { "${buffer.remaining()} trailing bytes" }
    validator.crossCheck(scopes, locals)
    return DecodedBranchDefinitions(PersistedBranchDefinitions(scopes, locals), null)
}

/**
 * Checks the decoded definitions against the persisted nodes and the writer's invariants. Node lookups are made once per table key and
 * once per distinct constant id, not once per triple.
 */
private class DefinitionValidator(private val nodeTags: BranchDefinitionNodeTags) {
    private val constants = IntOpenHashSet()

    fun isLocal(nodeId: Int): Boolean = nodeTags.tagOf(nodeId) == NodeSerializer.TAG_LOCAL_VARIABLE

    private fun isConstant(nodeId: Int): Boolean {
        if (nodeId == BranchScope.NO_CONSTANT || constants.contains(nodeId)) return true
        val tag = nodeTags.tagOf(nodeId)
        return (tag in NodeSerializer.TAG_INT_CONSTANT..NodeSerializer.TAG_ENUM_CONSTANT) && constants.add(nodeId)
    }

    /** One branch side: ordinals strictly increasing, constants nodes; the locals are checked against the tables. */
    fun readSide(buffer: ByteBuffer): IntArray = readEntries(buffer, NodeSerializer.NO_NODE)

    /** One local's table: every entry names [localId], ordinals strictly increase, constants are nodes. */
    fun readTable(buffer: ByteBuffer, localId: Int): IntArray = readEntries(buffer, localId)

    private fun readEntries(buffer: ByteBuffer, tableLocal: Int): IntArray {
        val packed = readPacked(buffer)
        val where = if (tableLocal == NodeSerializer.NO_NODE) "a branch side" else "the table of local $tableLocal"
        var base = 0
        var previousOrdinal = -1
        while (base < packed.size) {
            val ordinal = packed[base]
            require(
                ordinal >= 0 &&
                    (tableLocal == NodeSerializer.NO_NODE || packed[base + 1] == tableLocal) &&
                    isConstant(packed[base + 2])) {
                    "invalid definition ${describe(packed, base)} in $where"
                }
            require(ordinal > previousOrdinal) { "unordered definitions in $where" }
            previousOrdinal = ordinal
            base += BranchScope.DEFINITION_STRIDE
        }
        return packed
    }

    /** Every side definition is in its local's table, and every table is a local some side defines. */
    fun crossCheck(scopes: List<Pair<IntArray, IntArray>>, locals: Int2ObjectOpenHashMap<IntArray>) {
        val referenced = IntOpenHashSet()
        for ((trueSide, falseSide) in scopes) {
            requireDisjointSides(trueSide, falseSide)
            checkSideAgainstTables(trueSide, locals, referenced)
            checkSideAgainstTables(falseSide, locals, referenced)
        }
        require(referenced.size == locals.size) {
            "${locals.size - referenced.size} table(s) of locals without branch-side definitions"
        }
    }

    /** A statement belongs to at most one side of a scope; both sides are ordered, so this is a merge walk. */
    private fun requireDisjointSides(trueSide: IntArray, falseSide: IntArray) {
        var trueBase = 0
        var falseBase = 0
        while (trueBase < trueSide.size && falseBase < falseSide.size) {
            val trueOrdinal = trueSide[trueBase]
            val falseOrdinal = falseSide[falseBase]
            require(trueOrdinal != falseOrdinal) { "definition ${describe(trueSide, trueBase)} is on both sides of a scope" }
            if (trueOrdinal < falseOrdinal) trueBase += BranchScope.DEFINITION_STRIDE else falseBase += BranchScope.DEFINITION_STRIDE
        }
    }

    private fun checkSideAgainstTables(side: IntArray, locals: Int2ObjectOpenHashMap<IntArray>, referenced: IntOpenHashSet) {
        var base = 0
        while (base < side.size) {
            val local = side[base + 1]
            val table = locals.get(local)
            require(table != null) { "side definition ${describe(side, base)} has no table" }
            referenced.add(local)
            val index = indexOfOrdinal(table, side[base])
            require(index >= 0 && table[index + 2] == side[base + 2]) {
                "side definition ${describe(side, base)} is not in the table of local $local"
            }
            base += BranchScope.DEFINITION_STRIDE
        }
    }

    /** Binary search of a table (ordinals strictly increasing) for [ordinal]; the triple's base index or -1. */
    private fun indexOfOrdinal(table: IntArray, ordinal: Int): Int {
        var low = 0
        var high = table.size / BranchScope.DEFINITION_STRIDE - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = table[mid * BranchScope.DEFINITION_STRIDE]
            when {
                candidate < ordinal -> low = mid + 1
                candidate > ordinal -> high = mid - 1
                else -> return mid * BranchScope.DEFINITION_STRIDE
            }
        }
        return -1
    }

    private fun readPacked(buffer: ByteBuffer): IntArray {
        val count = buffer.getInt()
        val maxCount = buffer.remaining() / (BranchScope.DEFINITION_STRIDE * Int.SIZE_BYTES)
        require(count >= 0 && count <= maxCount) { "invalid definition count $count" }
        if (count == 0) return BranchScope.EMPTY_DEFINITIONS
        return IntArray(count * BranchScope.DEFINITION_STRIDE) { buffer.getInt() }
    }

    private fun describe(packed: IntArray, base: Int): String = "[${packed[base]}, ${packed[base + 1]}, ${packed[base + 2]}]"
}
