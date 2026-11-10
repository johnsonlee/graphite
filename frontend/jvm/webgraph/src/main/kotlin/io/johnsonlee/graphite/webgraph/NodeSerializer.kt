package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Serializes and deserializes graph nodes and metadata to/from binary files using [DataOutputStream]/[DataInputStream] with a [StringTable]
 * for string deduplication via front-coded compression (LAW/dsiutils).
 *
 * All string values (class names, method names, field names, annotation FQNs, etc.) are stored as 4-byte integer indices into the string
 * table instead of inline UTF strings. This provides significant size reduction through both deduplication and front-coding compression.
 *
 * Edge type encoding fits in 8 bits.
 *
 * v1/v2:
 * - Bits 0-1: edge family (0=DataFlow, 1=Call, 2=Type, 3=ControlFlow)
 * - Bits 2-5: subkind ordinal (DataFlowKind, ControlFlowKind, TypeRelation, or call flags)
 * - Bits 6-7: extra flags (isVirtual, isDynamic for CallEdge)
 *
 * v3:
 * - Bits 0-2: edge family (0=DataFlow, 1=Call, 2=Type, 3=ControlFlow, 4=Resource)
 * - Bits 3-6: subkind ordinal or call flags
 * - Bit 7: reserved
 * - Metadata includes class origins and artifact-level dependency summaries.
 *
 * [ControlFlowEdge.comparison] is stored separately since it does not fit in 8 bits.
 */
internal object NodeSerializer {

    // The trusted whole-JAR validation harness is built from the target revision.
    // Keep its reader entrypoints stable while the wire-format responsibilities
    // live in the individual codecs below.
    fun readMetadataOptionalSections(dis: DataInput, strings: StringTable, metadata: GraphMetadata): GraphMetadata =
        readGraphMetadataOptionalSections(dis, strings, metadata)

    fun loadMetadata(dis: DataInput, strings: StringTable): GraphMetadata = readGraphMetadata(dis, strings)

    fun decodeBranchDefinitionPreamble(
        preamble: ByteArray,
        payloadBytesInFile: Long,
        expectedPayloadDigest: ByteArray?,
        maxPayloadBytes: Int = MAX_BRANCH_DEFINITIONS_PAYLOAD_BYTES
    ): BranchDefinitionPreamble =
        decodePersistedBranchDefinitionPreamble(preamble, payloadBytesInFile, expectedPayloadDigest, maxPayloadBytes)

    fun interface NodeTagLookup {
        fun tagOf(nodeId: Int): Int
    }

    fun decodeBranchDefinitionPayload(
        payload: ByteArray,
        expectedScopeCount: Int,
        expectedPayloadDigest: ByteArray,
        nodeCount: Int,
        nodeTags: NodeTagLookup
    ): DecodedBranchDefinitions =
        decodePersistedBranchDefinitionPayload(
            payload, expectedScopeCount, expectedPayloadDigest, nodeCount, BranchDefinitionNodeTags(nodeTags::tagOf))

    fun tagOf(node: Node): Int = graphNodeTagOf(node)

    /** File type magic prefixes (3 bytes, big-endian high bits of the header int). */
    internal const val MAGIC_METADATA = 0x47524D00 // "GRM"
    internal const val MAGIC_NODEDATA = 0x47524E00 // "GRN"
    internal const val MAGIC_NODEINDEX = 0x47524900 // "GRI"
    internal const val MAGIC_COMPARISONS = 0x47524300 // "GRC"
    internal const val MAGIC_NODEOFFSETS = 0x47524C00 // "GRL"
    internal const val MAGIC_TYPEINDEX = 0x47525400 // "GRT"
    internal const val MAGIC_BRANCHDEFS = 0x47524400 // "GRD"
    internal const val MAGIC_METADATA_TRAILER = 0x47525800 // "GRX", appended to graph.metadata
    internal const val MAGIC_SYNTHETIC_IDENTITIES = 0x47525300 // "GRS", appended to graph.metadata
    internal const val MAGIC_CALL_SITE_ORDINALS = 0x47525100 // "GRQ", the graph.callsite-ordinals sidecar
    internal const val MAGIC_CALL_SITE_ORDINALS_BINDING = 0x47524200 // "GRB", the last section of graph.metadata

    /** Format version of the synthetic identity section, independent of [FORMAT_VERSION]. */
    internal const val SYNTHETIC_IDENTITIES_VERSION: Int = 1

    /** Bytes of one persisted fingerprint: 128 bits, see [io.johnsonlee.graphite.graph.Graph.syntheticIdentity]. */
    internal const val FINGERPRINT_BYTES: Int = 16
    internal const val HEX_RADIX = 16
    internal const val HEX_DIGIT_BITS = 4

    /** Format version of the optional `graph.branchdefs` sidecar, independent of [FORMAT_VERSION]. */
    internal const val BRANCH_DEFINITIONS_VERSION: Int = 1

    /** Length of the SHA-256 digests carried by the sidecar. */
    internal const val DIGEST_BYTES: Int = 32
    internal const val DIGEST_ALGORITHM: String = "SHA-256"

    /** Current format version (occupies the low byte of the 4-byte header int). */
    const val FORMAT_VERSION: Int = 3
    internal const val LEGACY_FORMAT_VERSION: Int = 1
    internal const val TRANSITIONAL_FORMAT_VERSION: Int = 2
    /** Version 3 introduced the labelled edge families and the artifact metadata. */
    const val LABELLED_EDGE_FORMAT_VERSION: Int = 3
    internal const val ARTIFACT_METADATA_FORMAT_VERSION: Int = 3
    /** Format version of the optional `graph.callsite-ordinals` sidecar, independent of [FORMAT_VERSION]. */
    internal const val CALL_SITE_ORDINALS_VERSION: Int = 4
    internal const val CALL_SITE_ORDINALS_BINDING_VERSION: Int = 2

    /** Write a 4-byte file header: 3-byte magic prefix | 1-byte version. */
    fun writeHeader(dos: DataOutputStream, magic: Int) {
        dos.writeInt(magic or FORMAT_VERSION)
    }

    /** Read and validate the 4-byte file header. Returns the format version. */
    fun readHeader(dis: DataInput, expectedMagic: Int): Int {
        val h = dis.readInt()
        val prefix = h and HEADER_MAGIC_MASK
        require(prefix == expectedMagic) {
            "Invalid file magic: expected 0x${expectedMagic.toString(HEX_RADIX)}, got 0x${prefix.toString(HEX_RADIX)}"
        }
        val version = h and BYTE_MASK
        validateVersion(version, expectedMagic)
        return version
    }

    /** Overload for [RandomAccessFile]. */
    fun readHeader(raf: RandomAccessFile, expectedMagic: Int): Int {
        val h = raf.readInt()
        val prefix = h and HEADER_MAGIC_MASK
        require(prefix == expectedMagic) {
            "Invalid file magic: expected 0x${expectedMagic.toString(HEX_RADIX)}, got 0x${prefix.toString(HEX_RADIX)}"
        }
        val version = h and BYTE_MASK
        validateVersion(version, expectedMagic)
        return version
    }

    private fun validateVersion(version: Int, expectedMagic: Int) {
        require(version in LEGACY_FORMAT_VERSION..FORMAT_VERSION) {
            "Unsupported GraphStore format version $version for 0x${expectedMagic.toString(HEX_RADIX)}. " +
                "This build supports versions $LEGACY_FORMAT_VERSION to $FORMAT_VERSION."
        }
    }

    // Node type tags
    internal const val TAG_INT_CONSTANT = 0
    internal const val TAG_STRING_CONSTANT = 1
    internal const val TAG_LONG_CONSTANT = 2
    internal const val TAG_FLOAT_CONSTANT = 3
    internal const val TAG_DOUBLE_CONSTANT = 4
    internal const val TAG_BOOLEAN_CONSTANT = 5
    internal const val TAG_NULL_CONSTANT = 6
    internal const val TAG_ENUM_CONSTANT = 7
    internal const val TAG_LOCAL_VARIABLE = 8
    internal const val TAG_FIELD_NODE = 9
    internal const val TAG_PARAMETER_NODE = 10
    internal const val TAG_RETURN_NODE = 11
    internal const val TAG_CALL_SITE_NODE = 12
    internal const val TAG_ANNOTATION_NODE = 13
    internal const val TAG_RESOURCE_VALUE_NODE = 14
    internal const val TAG_RESOURCE_FILE_NODE = 15

    // Value type tags (for heterogeneous value lists like enum constructor args)
    internal const val VAL_INT = 0
    internal const val VAL_LONG = 1
    internal const val VAL_STRING = 2
    internal const val VAL_FLOAT = 3
    internal const val VAL_DOUBLE = 4
    internal const val VAL_BOOLEAN = 5
    internal const val VAL_NULL = 6
    internal const val VAL_ENUM_REF = 7
    internal const val VAL_LIST = 8

    /**
     * Encode an edge type into an 8-bit label.
     *
     * v3 layout:
     * ```
     * bits 0-2: edge family (0=DataFlow, 1=Call, 2=Type, 3=ControlFlow, 4=Resource)
     * bits 3-6: subkind ordinal or call flags (bit3=isVirtual, bit4=isDynamic)
     * bit 7: reserved
     * ```
     */
    fun encodeEdge(edge: Edge): Int =
        when (edge) {
            is DataFlowEdge -> EDGE_FAMILY_DATAFLOW or (edge.kind.ordinal shl V3_EDGE_KIND_SHIFT)
            is CallEdge ->
                EDGE_FAMILY_CALL or
                    ((if (edge.isVirtual) 1 else 0) shl V3_EDGE_KIND_SHIFT) or
                    ((if (edge.isDynamic) 1 else 0) shl CALL_EDGE_DYNAMIC_SHIFT)
            is TypeEdge -> EDGE_FAMILY_TYPE or (edge.kind.ordinal shl V3_EDGE_KIND_SHIFT)
            is ControlFlowEdge -> EDGE_FAMILY_CONTROL_FLOW or (edge.kind.ordinal shl V3_EDGE_KIND_SHIFT)
            is ResourceEdge -> EDGE_FAMILY_RESOURCE or (edge.kind.ordinal shl V3_EDGE_KIND_SHIFT)
        }

    /**
     * Decode an 8-bit label back into an [Edge].
     *
     * [comparison] must be supplied externally for [ControlFlowEdge] edges that carried a non-null comparison at save time.
     */
    fun decodeEdge(label: Int, from: NodeId, to: NodeId, comparison: BranchComparison? = null, version: Int = FORMAT_VERSION): Edge {
        return if (version >= LABELLED_EDGE_FORMAT_VERSION) {
            decodeEdgeV3(label, from, to, comparison)
        } else {
            decodeEdgeV2(label, from, to, comparison)
        }
    }

    private fun decodeEdgeV2(label: Int, from: NodeId, to: NodeId, comparison: BranchComparison?): Edge {
        val family = label and V2_EDGE_FAMILY_MASK
        return when (family) {
            EDGE_FAMILY_DATAFLOW -> DataFlowEdge(from, to, DataFlowKind.entries[(label shr V2_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            EDGE_FAMILY_CALL ->
                CallEdge(
                    from,
                    to,
                    isVirtual = ((label shr CALL_EDGE_VIRTUAL_SHIFT_V2) and 1) == 1,
                    isDynamic = ((label shr CALL_EDGE_DYNAMIC_SHIFT_V2) and 1) == 1)
            EDGE_FAMILY_TYPE -> TypeEdge(from, to, TypeRelation.entries[(label shr V2_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            // The v2 family mask has exactly two bits; the remaining value is ControlFlow.
            else -> ControlFlowEdge(from, to, ControlFlowKind.entries[(label shr V2_EDGE_KIND_SHIFT) and EDGE_KIND_MASK], comparison)
        }
    }

    private fun decodeEdgeV3(label: Int, from: NodeId, to: NodeId, comparison: BranchComparison?): Edge {
        val family = label and V3_EDGE_FAMILY_MASK
        return when (family) {
            EDGE_FAMILY_DATAFLOW -> DataFlowEdge(from, to, DataFlowKind.entries[(label shr V3_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            EDGE_FAMILY_CALL ->
                CallEdge(
                    from,
                    to,
                    isVirtual = ((label shr V3_EDGE_KIND_SHIFT) and 1) == 1,
                    isDynamic = ((label shr CALL_EDGE_DYNAMIC_SHIFT) and 1) == 1)
            EDGE_FAMILY_TYPE -> TypeEdge(from, to, TypeRelation.entries[(label shr V3_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            EDGE_FAMILY_CONTROL_FLOW ->
                ControlFlowEdge(from, to, ControlFlowKind.entries[(label shr V3_EDGE_KIND_SHIFT) and EDGE_KIND_MASK], comparison)
            EDGE_FAMILY_RESOURCE -> ResourceEdge(from, to, ResourceRelation.entries[(label shr V3_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            else -> throw IllegalArgumentException("Unknown edge family: $family")
        }
    }

    /** Header and the copy of the binding digest, before the sidecar's index. */
    internal const val CALL_SITE_ORDINALS_PREAMBLE_BYTES = Int.SIZE_BYTES + DIGEST_BYTES
    /** Preamble, count and origin count, before the block heads. */
    internal const val CALL_SITE_ORDINALS_HEADER_BYTES = CALL_SITE_ORDINALS_PREAMBLE_BYTES + 2 * Int.SIZE_BYTES
    internal const val CALL_SITE_ORDINAL_ENTRY_BYTES = 2 * Int.SIZE_BYTES
    /** Entries per block head: a lookup searches one block, verified on its first touch. */
    internal const val CALL_SITE_ORDINAL_BLOCK_ENTRIES = 256
    /** Bytes per verified block of entries: the ordinal entries' blocks line up with their heads. */
    internal const val CALL_SITE_ORDINAL_BLOCK_BYTES = CALL_SITE_ORDINAL_BLOCK_ENTRIES * CALL_SITE_ORDINAL_ENTRY_BYTES
    internal const val CALL_SITE_ORDINALS_BINDING_BYTES = Int.SIZE_BYTES + DIGEST_BYTES

    /** One persisted entry: the member's string table index and the raw fingerprint. */
    internal const val SYNTHETIC_IDENTITY_ENTRY_BYTES = Int.SIZE_BYTES + FINGERPRINT_BYTES

    /** More entries than any corpus has; a count above it is a corrupt section, not an allocation. */
    internal const val MAX_SYNTHETIC_IDENTITIES = 1 shl 24

    internal const val HEX_DIGITS = "0123456789abcdef"
    internal const val NIBBLE_BITS = 4
    internal const val NIBBLE_MASK = 0xF

    /** Bytes before the payload: header, payload length, payload digest. */
    internal const val BRANCH_DEFINITIONS_PREAMBLE_BYTES: Int = Int.SIZE_BYTES + Int.SIZE_BYTES + DIGEST_BYTES

    /** Bytes of the `graph.metadata` trailer: magic and the sidecar payload digest. */
    internal const val METADATA_TRAILER_BYTES: Int = Int.SIZE_BYTES + DIGEST_BYTES

    /** [NodeTagLookup] result for an id that is not a persisted node. */
    const val NO_NODE = -1

    /** Largest sidecar payload a reader will allocate for; a declared length above it is corrupt. */
    internal const val MAX_BRANCH_DEFINITIONS_PAYLOAD_BYTES: Int = 512 * 1024 * 1024

    fun writeComparisons(dos: DataOutputStream, comparisons: Map<Long, BranchComparison>) {
        writeHeader(dos, MAGIC_COMPARISONS)
        dos.writeInt(comparisons.size)
        for ((key, comp) in comparisons) {
            dos.writeLong(key)
            dos.writeInt(comp.operator.ordinal)
            dos.writeInt(comp.comparandNodeId.value)
        }
    }

    fun readComparisons(dis: DataInput): Map<Long, BranchComparison> {
        readHeader(dis, MAGIC_COMPARISONS)
        val count = dis.readInt()
        val result = HashMap<Long, BranchComparison>(count)
        repeat(count) {
            val key = dis.readLong()
            val op = ComparisonOp.entries[dis.readInt()]
            val comparandId = NodeId(dis.readInt())
            result[key] = BranchComparison(op, comparandId)
        }
        return result
    }
}

/** Holds all non-graph metadata that doesn't fit in the adjacency structure. */
data class GraphMetadata(
    val methods: Map<String, MethodDescriptor>,
    val supertypes: Map<String, Set<TypeDescriptor>>,
    val subtypes: Map<String, Set<TypeDescriptor>>,
    val enumValues: Map<String, List<Any?>>,
    val classOrigins: Map<String, String>,
    val artifactDependencies: Map<String, Map<String, Int>>,
    val memberAnnotations: Map<String, Map<String, Map<String, Any?>>>,
    val branchScopes: List<BranchScopeData>,
    /** Packed definitions per tracked local (see [io.johnsonlee.graphite.graph.Graph.localDefinitions]); save side only. */
    val localDefinitions: Map<Int, IntArray> = emptyMap(),
    /** SHA-256 of the `graph.branchdefs` payload this metadata was written with, from its trailer; load side only. */
    val branchDefinitionDigest: ByteArray? = null,
    /** Stable identities of synthetic members, see [io.johnsonlee.graphite.graph.Graph.syntheticIdentities]. */
    val syntheticIdentities: Map<String, String> = emptyMap(),
    /** SHA-256 of the `graph.callsite-ordinals` entries this metadata was written with, from its last section; load side only. */
    val callSiteOrdinalDigest: ByteArray? = null
)

/**
 * The synthetic identity section as read: [records] holds `count` records of a string table index and a raw fingerprint, decoded into a map
 * on first use (see [readGraphMetadataOptionalSections]).
 */
internal class LazySyntheticIdentities(private val count: Int, private val records: ByteArray, private val strings: StringTable) :
    AbstractMap<String, String>() {
    private val decoded: Map<String, String> by lazy {
        val buffer = ByteBuffer.wrap(records)
        val fingerprint = ByteArray(NodeSerializer.FINGERPRINT_BYTES)
        val map = LinkedHashMap<String, String>(count * 2)
        repeat(count) {
            val member = strings.get(buffer.getInt())
            buffer.get(fingerprint)
            map[member] = encodeGraphFingerprint(fingerprint)
        }
        map
    }

    override val entries: Set<Map.Entry<String, String>>
        get() = decoded.entries

    override val size: Int
        get() = count

    override fun get(key: String): String? = decoded[key]

    override fun containsKey(key: String): Boolean = decoded.containsKey(key)
}

/** A sidecar as [encodeBranchDefinitions] produces it, with the digest `graph.metadata` binds to. */
class EncodedBranchDefinitions(val bytes: ByteArray, val payloadDigest: ByteArray)

/** Outcome of [decodePersistedBranchDefinitionPreamble]: the accepted length and digest, or the [rejection]. */
data class BranchDefinitionPreamble(val payloadLength: Int, val payloadDigest: ByteArray?, val rejection: String?) {
    companion object {
        fun rejected(reason: String) = BranchDefinitionPreamble(0, null, reason)
    }
}

/**
 * The `graph.branchdefs` sidecar as loaded: one `(true, false)` packed pair per metadata branch scope, and per-local tables held in an
 * unboxed map whose size the decoder bounds by the graph's node count.
 */
data class PersistedBranchDefinitions(val scopes: List<Pair<IntArray, IntArray>>, val locals: Map<Int, IntArray>) {
    companion object {
        val EMPTY = PersistedBranchDefinitions(emptyList(), emptyMap())
    }
}

/** Outcome of [decodePersistedBranchDefinitionPayload]: either [definitions] or the [rejection] reason. */
data class DecodedBranchDefinitions(val definitions: PersistedBranchDefinitions?, val rejection: String?) {
    companion object {
        fun rejected(reason: String) = DecodedBranchDefinitions(null, reason)
    }
}

data class BranchScopeData(
    val conditionNodeId: Int,
    val method: MethodDescriptor,
    val comparison: BranchComparison,
    val trueBranchNodeIds: IntArray,
    val falseBranchNodeIds: IntArray,
    /** Packed `[stmtOrdinal, localNodeId, constantNodeId]*`, see [BranchScope.packDefinitions]. */
    val trueDefinitions: IntArray = BranchScope.EMPTY_DEFINITIONS,
    val falseDefinitions: IntArray = BranchScope.EMPTY_DEFINITIONS
) {
    /** Materialise with the sidecar's [definitions] for this scope, falling back to the fields. */
    fun toBranchScope(definitions: Pair<IntArray, IntArray>? = null): BranchScope =
        BranchScope(
            conditionNodeId = NodeId(conditionNodeId),
            method = method,
            comparison = comparison,
            trueBranchNodeIds = IntOpenHashSet(trueBranchNodeIds),
            falseBranchNodeIds = IntOpenHashSet(falseBranchNodeIds),
            trueDefinitions = BranchScope.unpackDefinitions(definitions?.first ?: trueDefinitions),
            falseDefinitions = BranchScope.unpackDefinitions(definitions?.second ?: falseDefinitions))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BranchScopeData) return false
        return conditionNodeId == other.conditionNodeId &&
            method == other.method &&
            comparison == other.comparison &&
            trueBranchNodeIds.contentEquals(other.trueBranchNodeIds) &&
            falseBranchNodeIds.contentEquals(other.falseBranchNodeIds) &&
            trueDefinitions.contentEquals(other.trueDefinitions) &&
            falseDefinitions.contentEquals(other.falseDefinitions)
    }

    override fun hashCode(): Int {
        var result = conditionNodeId
        result = 31 * result + method.hashCode()
        result = 31 * result + comparison.hashCode()
        result = 31 * result + trueBranchNodeIds.contentHashCode()
        result = 31 * result + falseBranchNodeIds.contentHashCode()
        result = 31 * result + trueDefinitions.contentHashCode()
        result = 31 * result + falseDefinitions.contentHashCode()
        return result
    }
}
