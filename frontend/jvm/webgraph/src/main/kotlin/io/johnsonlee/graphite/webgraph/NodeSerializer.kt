package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.core.ValueNode
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Serializes and deserializes graph nodes and metadata to/from binary files
 * using [DataOutputStream]/[DataInputStream] with a [StringTable] for string
 * deduplication via front-coded compression (LAW/dsiutils).
 *
 * All string values (class names, method names, field names, annotation FQNs,
 * etc.) are stored as 4-byte integer indices into the string table instead of
 * inline UTF strings. This provides significant size reduction through both
 * deduplication and front-coding compression.
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

    /** File type magic prefixes (3 bytes, big-endian high bits of the header int). */
    internal const val MAGIC_METADATA    = 0x47524D00  // "GRM"
    internal const val MAGIC_NODEDATA    = 0x47524E00  // "GRN"
    internal const val MAGIC_NODEINDEX   = 0x47524900  // "GRI"
    internal const val MAGIC_COMPARISONS = 0x47524300  // "GRC"
    internal const val MAGIC_NODEOFFSETS = 0x47524C00  // "GRL"
    internal const val MAGIC_TYPEINDEX   = 0x47525400  // "GRT"
    internal const val MAGIC_BRANCHDEFS  = 0x47524400  // "GRD"
    internal const val MAGIC_METADATA_TRAILER = 0x47525800  // "GRX", appended to graph.metadata
    internal const val MAGIC_SYNTHETIC_IDENTITIES = 0x47525300  // "GRS", appended to graph.metadata

    /** Format version of the synthetic identity section, independent of [FORMAT_VERSION]. */
    internal const val SYNTHETIC_IDENTITIES_VERSION: Int = 1

    /** Bytes of one persisted fingerprint: 128 bits, see [io.johnsonlee.graphite.graph.Graph.syntheticIdentity]. */
    internal const val FINGERPRINT_BYTES: Int = 16
    private const val HEX_RADIX = 16
    private const val HEX_DIGIT_BITS = 4

    /** Format version of the optional `graph.branchdefs` sidecar, independent of [FORMAT_VERSION]. */
    internal const val BRANCH_DEFINITIONS_VERSION: Int = 1

    /** Length of the SHA-256 digests carried by the sidecar. */
    internal const val DIGEST_BYTES: Int = 32
    internal const val DIGEST_ALGORITHM: String = "SHA-256"

    /** Current format version (occupies the low byte of the 4-byte header int). */
    const val FORMAT_VERSION: Int = 3
    private const val LEGACY_FORMAT_VERSION: Int = 1
    private const val TRANSITIONAL_FORMAT_VERSION: Int = 2
    private const val ARTIFACT_METADATA_FORMAT_VERSION: Int = 3

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
        require(
            version == LEGACY_FORMAT_VERSION ||
                version == TRANSITIONAL_FORMAT_VERSION ||
                version == FORMAT_VERSION
        ) {
            "Unsupported GraphStore format version $version for 0x${expectedMagic.toString(HEX_RADIX)}. " +
                "This build supports versions $LEGACY_FORMAT_VERSION, $TRANSITIONAL_FORMAT_VERSION and $FORMAT_VERSION."
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
    private const val VAL_INT = 0
    private const val VAL_LONG = 1
    private const val VAL_STRING = 2
    private const val VAL_FLOAT = 3
    private const val VAL_DOUBLE = 4
    private const val VAL_BOOLEAN = 5
    private const val VAL_NULL = 6
    private const val VAL_ENUM_REF = 7
    private const val VAL_LIST = 8

    // ========================================================================
    // Edge encoding / decoding
    // ========================================================================

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
    fun encodeEdge(edge: Edge): Int = when (edge) {
        is DataFlowEdge -> EDGE_FAMILY_DATAFLOW or (edge.kind.ordinal shl V3_EDGE_KIND_SHIFT)
        is CallEdge -> EDGE_FAMILY_CALL or
                ((if (edge.isVirtual) 1 else 0) shl V3_EDGE_KIND_SHIFT) or
                ((if (edge.isDynamic) 1 else 0) shl CALL_EDGE_DYNAMIC_SHIFT)
        is TypeEdge -> EDGE_FAMILY_TYPE or (edge.kind.ordinal shl V3_EDGE_KIND_SHIFT)
        is ControlFlowEdge -> EDGE_FAMILY_CONTROL_FLOW or (edge.kind.ordinal shl V3_EDGE_KIND_SHIFT)
        is ResourceEdge -> EDGE_FAMILY_RESOURCE or (edge.kind.ordinal shl V3_EDGE_KIND_SHIFT)
    }

    /**
     * Decode an 8-bit label back into an [Edge].
     *
     * [comparison] must be supplied externally for [ControlFlowEdge] edges that
     * carried a non-null comparison at save time.
     */
    fun decodeEdge(
        label: Int,
        from: NodeId,
        to: NodeId,
        comparison: BranchComparison? = null,
        version: Int = FORMAT_VERSION
    ): Edge {
        return if (version >= FORMAT_VERSION) decodeEdgeV3(label, from, to, comparison) else decodeEdgeV2(label, from, to, comparison)
    }

    private fun decodeEdgeV2(label: Int, from: NodeId, to: NodeId, comparison: BranchComparison?): Edge {
        val family = label and V2_EDGE_FAMILY_MASK
        return when (family) {
            EDGE_FAMILY_DATAFLOW -> DataFlowEdge(from, to, DataFlowKind.entries[(label shr V2_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            EDGE_FAMILY_CALL -> CallEdge(
                from, to,
                isVirtual = ((label shr CALL_EDGE_VIRTUAL_SHIFT_V2) and 1) == 1,
                isDynamic = ((label shr CALL_EDGE_DYNAMIC_SHIFT_V2) and 1) == 1
            )
            EDGE_FAMILY_TYPE -> TypeEdge(from, to, TypeRelation.entries[(label shr V2_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            EDGE_FAMILY_CONTROL_FLOW -> ControlFlowEdge(
                from,
                to,
                ControlFlowKind.entries[(label shr V2_EDGE_KIND_SHIFT) and EDGE_KIND_MASK],
                comparison
            )
            else -> throw IllegalArgumentException("Unknown edge family: $family")
        }
    }

    private fun decodeEdgeV3(label: Int, from: NodeId, to: NodeId, comparison: BranchComparison?): Edge {
        val family = label and V3_EDGE_FAMILY_MASK
        return when (family) {
            EDGE_FAMILY_DATAFLOW -> DataFlowEdge(from, to, DataFlowKind.entries[(label shr V3_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            EDGE_FAMILY_CALL -> CallEdge(
                from, to,
                isVirtual = ((label shr V3_EDGE_KIND_SHIFT) and 1) == 1,
                isDynamic = ((label shr CALL_EDGE_DYNAMIC_SHIFT) and 1) == 1
            )
            EDGE_FAMILY_TYPE -> TypeEdge(from, to, TypeRelation.entries[(label shr V3_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            EDGE_FAMILY_CONTROL_FLOW -> ControlFlowEdge(
                from,
                to,
                ControlFlowKind.entries[(label shr V3_EDGE_KIND_SHIFT) and EDGE_KIND_MASK],
                comparison
            )
            EDGE_FAMILY_RESOURCE -> ResourceEdge(from, to, ResourceRelation.entries[(label shr V3_EDGE_KIND_SHIFT) and EDGE_KIND_MASK])
            else -> throw IllegalArgumentException("Unknown edge family: $family")
        }
    }

    // ========================================================================
    // String collection -- gathers all strings from nodes and metadata
    // ========================================================================

    /**
     * Collect all unique strings from a list of nodes.
     */
    fun collectNodeStrings(nodes: Iterable<Node>, dest: MutableSet<String>) {
        for (node in nodes) {
            collectNodeStrings(node, dest)
        }
    }

    fun collectNodeStrings(node: Node, dest: MutableSet<String>) {
        when (node) {
            is StringConstant -> dest.add(node.value)
            is EnumConstant -> {
                dest.add(node.enumType.className)
                dest.add(node.enumName)
                collectAnyValueStrings(node.constructorArgs, dest)
            }
            is LocalVariable -> {
                dest.add(node.name)
                dest.add(node.type.className)
                collectMethodDescriptorStrings(node.method, dest)
            }
            is FieldNode -> {
                dest.add(node.descriptor.declaringClass.className)
                dest.add(node.descriptor.name)
                dest.add(node.descriptor.type.className)
            }
            is ParameterNode -> {
                dest.add(node.type.className)
                collectMethodDescriptorStrings(node.method, dest)
            }
            is ReturnNode -> {
                collectMethodDescriptorStrings(node.method, dest)
                node.actualType?.let { dest.add(it.className) }
            }
            is ResourceFileNode -> {
                dest.add(node.path)
                dest.add(node.source)
                dest.add(node.format)
                node.profile?.let(dest::add)
            }
            is ResourceValueNode -> {
                dest.add(node.path)
                dest.add(node.key)
                dest.add(node.format)
                node.profile?.let(dest::add)
                collectAnyValueString(node.value, dest)
            }
            is CallSiteNode -> {
                collectMethodDescriptorStrings(node.caller, dest)
                collectMethodDescriptorStrings(node.callee, dest)
            }
            is AnnotationNode -> {
                dest.add(node.name)
                dest.add(node.className)
                dest.add(node.memberName)
                for ((k, v) in node.values) {
                    dest.add(k)
                    collectAnyValueString(v, dest)
                }
            }
            else -> {} // IntConstant, LongConstant, FloatConstant, DoubleConstant, BooleanConstant, NullConstant
        }
    }

    /**
     * Collect all unique strings from metadata.
     */
    fun collectMetadataStrings(metadata: GraphMetadata, dest: MutableSet<String>) {
        // Methods
        for ((_, md) in metadata.methods) {
            collectMethodDescriptorStrings(md, dest)
        }

        // Type hierarchy
        for ((typeName, sups) in metadata.supertypes) {
            dest.add(typeName)
            for (s in sups) dest.add(s.className)
        }
        for ((typeName, subs) in metadata.subtypes) {
            dest.add(typeName)
            for (s in subs) dest.add(s.className)
        }

        // Enum values
        for ((key, values) in metadata.enumValues) {
            dest.add(key)
            collectAnyValueStrings(values, dest)
        }

        for ((className, source) in metadata.classOrigins) {
            dest.add(className)
            dest.add(source)
        }

        for ((fromArtifact, dependencies) in metadata.artifactDependencies) {
            dest.add(fromArtifact)
            for ((toArtifact, _) in dependencies) {
                dest.add(toArtifact)
            }
        }

        // Member annotations
        for ((key, annotations) in metadata.memberAnnotations) {
            dest.add(key)
            for ((fqn, attrs) in annotations) {
                dest.add(fqn)
                for ((k, v) in attrs) {
                    dest.add(k)
                    collectAnyValueString(v, dest)
                }
            }
        }

        // Branch scopes
        for (bs in metadata.branchScopes) {
            collectMethodDescriptorStrings(bs.method, dest)
        }

        // Synthetic identities: keys only, fingerprints are raw bytes
        for ((member, _) in metadata.syntheticIdentities) {
            dest.add(member)
        }
    }

    private fun collectMethodDescriptorStrings(md: MethodDescriptor, dest: MutableSet<String>) {
        dest.add(md.declaringClass.className)
        dest.add(md.name)
        for (p in md.parameterTypes) dest.add(p.className)
        dest.add(md.returnType.className)
    }

    private fun collectAnyValueStrings(values: List<Any?>, dest: MutableSet<String>) {
        for (value in values) {
            collectAnyValueString(value, dest)
        }
    }

    private fun collectAnyValueString(value: Any?, dest: MutableSet<String>) {
        when (value) {
            is String -> dest.add(value)
            is EnumValueReference -> {
                dest.add(value.enumClass)
                dest.add(value.enumName)
            }
            is List<*> -> value.forEach { collectAnyValueString(it, dest) }
            is Int, is Long, is Float, is Double, is Boolean, null -> {}
            else -> dest.add(value.toString())
        }
    }

    // ========================================================================
    // Node writing / reading (string-table-aware)
    // ========================================================================

    fun tagOf(node: Node): Int = when (node) {
        is IntConstant -> TAG_INT_CONSTANT
        is StringConstant -> TAG_STRING_CONSTANT
        is LongConstant -> TAG_LONG_CONSTANT
        is FloatConstant -> TAG_FLOAT_CONSTANT
        is DoubleConstant -> TAG_DOUBLE_CONSTANT
        is BooleanConstant -> TAG_BOOLEAN_CONSTANT
        is NullConstant -> TAG_NULL_CONSTANT
        is EnumConstant -> TAG_ENUM_CONSTANT
        is LocalVariable -> TAG_LOCAL_VARIABLE
        is FieldNode -> TAG_FIELD_NODE
        is ParameterNode -> TAG_PARAMETER_NODE
        is ReturnNode -> TAG_RETURN_NODE
        is CallSiteNode -> TAG_CALL_SITE_NODE
        is AnnotationNode -> TAG_ANNOTATION_NODE
        is ResourceFileNode -> TAG_RESOURCE_FILE_NODE
        is ResourceValueNode -> TAG_RESOURCE_VALUE_NODE
    }

    fun writeNode(dos: DataOutputStream, node: Node, strings: StringTable): Int {
        dos.writeInt(node.id.value)
        val tag = tagOf(node)
        dos.writeByte(tag)
        // Type-specific fields
        when (node) {
            is IntConstant -> dos.writeInt(node.value)
            is StringConstant -> dos.writeInt(strings.indexOf(node.value))
            is LongConstant -> dos.writeLong(node.value)
            is FloatConstant -> dos.writeFloat(node.value)
            is DoubleConstant -> dos.writeDouble(node.value)
            is BooleanConstant -> dos.writeBoolean(node.value)
            is NullConstant -> {} // no additional data
            is EnumConstant -> {
                dos.writeInt(strings.indexOf(node.enumType.className))
                dos.writeInt(strings.indexOf(node.enumName))
                dos.writeInt(node.constructorArgs.size)
                for (arg in node.constructorArgs) writeAnyValue(dos, arg, strings)
            }
            is LocalVariable -> {
                dos.writeInt(strings.indexOf(node.name))
                dos.writeInt(strings.indexOf(node.type.className))
                writeMethodDescriptor(dos, node.method, strings)
            }
            is FieldNode -> {
                dos.writeInt(strings.indexOf(node.descriptor.declaringClass.className))
                dos.writeInt(strings.indexOf(node.descriptor.name))
                dos.writeInt(strings.indexOf(node.descriptor.type.className))
                dos.writeBoolean(node.isStatic)
            }
            is ParameterNode -> {
                dos.writeInt(node.index)
                dos.writeInt(strings.indexOf(node.type.className))
                writeMethodDescriptor(dos, node.method, strings)
            }
            is ReturnNode -> {
                writeMethodDescriptor(dos, node.method, strings)
                dos.writeBoolean(node.actualType != null)
                if (node.actualType != null) dos.writeInt(strings.indexOf(node.actualType!!.className))
            }
            is ResourceFileNode -> {
                dos.writeInt(strings.indexOf(node.path))
                dos.writeInt(strings.indexOf(node.source))
                dos.writeInt(strings.indexOf(node.format))
                dos.writeBoolean(node.profile != null)
                if (node.profile != null) dos.writeInt(strings.indexOf(node.profile!!))
            }
            is ResourceValueNode -> {
                dos.writeInt(strings.indexOf(node.path))
                dos.writeInt(strings.indexOf(node.key))
                writeAnyValue(dos, node.value, strings)
                dos.writeInt(strings.indexOf(node.format))
                dos.writeBoolean(node.profile != null)
                if (node.profile != null) dos.writeInt(strings.indexOf(node.profile!!))
            }
            is CallSiteNode -> {
                writeMethodDescriptor(dos, node.caller, strings)
                writeMethodDescriptor(dos, node.callee, strings)
                dos.writeInt(node.lineNumber ?: -1)
                dos.writeInt(node.receiver?.value ?: -1)
                dos.writeInt(node.arguments.size)
                for (arg in node.arguments) dos.writeInt(arg.value)
            }
            is AnnotationNode -> {
                dos.writeInt(strings.indexOf(node.name))
                dos.writeInt(strings.indexOf(node.className))
                dos.writeInt(strings.indexOf(node.memberName))
                dos.writeInt(node.values.size)
                for ((k, v) in node.values) {
                    dos.writeInt(strings.indexOf(k))
                    writeAnyValue(dos, v, strings)
                }
            }
        }
        return tag
    }

    fun readNode(dis: DataInput, strings: StringTable, formatVersion: Int = FORMAT_VERSION): Node {
        val id = NodeId(dis.readInt())
        return when (val tag = dis.readByte().toInt()) {
            TAG_INT_CONSTANT -> IntConstant(id, dis.readInt())
            TAG_STRING_CONSTANT -> StringConstant(id, strings.get(dis.readInt()))
            TAG_LONG_CONSTANT -> LongConstant(id, dis.readLong())
            TAG_FLOAT_CONSTANT -> FloatConstant(id, dis.readFloat())
            TAG_DOUBLE_CONSTANT -> DoubleConstant(id, dis.readDouble())
            TAG_BOOLEAN_CONSTANT -> BooleanConstant(id, dis.readBoolean())
            TAG_NULL_CONSTANT -> NullConstant(id)
            TAG_ENUM_CONSTANT -> {
                val enumType = TypeDescriptor(strings.get(dis.readInt()))
                val enumName = strings.get(dis.readInt())
                val argCount = dis.readInt()
                val args = (0 until argCount).map { readAnyValue(dis, strings, formatVersion) }
                EnumConstant(id, enumType, enumName, args)
            }
            TAG_LOCAL_VARIABLE -> {
                val name = strings.get(dis.readInt())
                val type = TypeDescriptor(strings.get(dis.readInt()))
                val method = readMethodDescriptor(dis, strings)
                LocalVariable(id, name, type, method)
            }
            TAG_FIELD_NODE -> {
                val declClass = TypeDescriptor(strings.get(dis.readInt()))
                val name = strings.get(dis.readInt())
                val fieldType = TypeDescriptor(strings.get(dis.readInt()))
                val isStatic = dis.readBoolean()
                FieldNode(id, FieldDescriptor(declClass, name, fieldType), isStatic)
            }
            TAG_PARAMETER_NODE -> {
                val index = dis.readInt()
                val type = TypeDescriptor(strings.get(dis.readInt()))
                val method = readMethodDescriptor(dis, strings)
                ParameterNode(id, index, type, method)
            }
            TAG_RETURN_NODE -> {
                val method = readMethodDescriptor(dis, strings)
                val hasActualType = dis.readBoolean()
                val actualType = if (hasActualType) TypeDescriptor(strings.get(dis.readInt())) else null
                ReturnNode(id, method, actualType)
            }
            TAG_RESOURCE_FILE_NODE -> {
                val path = strings.get(dis.readInt())
                val source = strings.get(dis.readInt())
                val format = strings.get(dis.readInt())
                val profile = if (dis.readBoolean()) strings.get(dis.readInt()) else null
                ResourceFileNode(id, path, source, format, profile)
            }
            TAG_RESOURCE_VALUE_NODE -> {
                val path = strings.get(dis.readInt())
                val key = strings.get(dis.readInt())
                val value = readAnyValue(dis, strings, formatVersion)
                val format = strings.get(dis.readInt())
                val profile = if (dis.readBoolean()) strings.get(dis.readInt()) else null
                ResourceValueNode(id, path, key, value, format, profile)
            }
            TAG_CALL_SITE_NODE -> {
                val caller = readMethodDescriptor(dis, strings)
                val callee = readMethodDescriptor(dis, strings)
                val lineNumber = dis.readInt().let { if (it == -1) null else it }
                val receiver = dis.readInt().let { if (it == -1) null else NodeId(it) }
                val argCount = dis.readInt()
                val arguments = (0 until argCount).map { NodeId(dis.readInt()) }
                CallSiteNode(id, caller, callee, lineNumber, receiver, arguments)
            }
            TAG_ANNOTATION_NODE -> {
                val name = strings.get(dis.readInt())
                val className = strings.get(dis.readInt())
                val memberName = strings.get(dis.readInt())
                val kvCount = dis.readInt()
                val values = mutableMapOf<String, Any?>()
                repeat(kvCount) {
                    val k = strings.get(dis.readInt())
                    val v = readAnnotationValue(dis, strings, formatVersion)
                    values[k] = v
                }
                AnnotationNode(id, name, className, memberName, values)
            }
            else -> throw IllegalArgumentException("Unknown node tag: $tag")
        }
    }

    // ========================================================================
    // Metadata writing / reading (string-table-aware)
    // ========================================================================

    fun saveMetadata(metadata: GraphMetadata, dos: DataOutputStream, strings: StringTable) {
        writeHeader(dos, MAGIC_METADATA)
        // Methods
        dos.writeInt(metadata.methods.size)
        for ((_, md) in metadata.methods) {
            writeMethodDescriptor(dos, md, strings)
        }

        // Type hierarchy: supertypes
        dos.writeInt(metadata.supertypes.size)
        for ((typeName, sups) in metadata.supertypes) {
            dos.writeInt(strings.indexOf(typeName))
            dos.writeInt(sups.size)
            for (s in sups) dos.writeInt(strings.indexOf(s.className))
        }

        // Type hierarchy: subtypes
        dos.writeInt(metadata.subtypes.size)
        for ((typeName, subs) in metadata.subtypes) {
            dos.writeInt(strings.indexOf(typeName))
            dos.writeInt(subs.size)
            for (s in subs) dos.writeInt(strings.indexOf(s.className))
        }

        // Enum values
        dos.writeInt(metadata.enumValues.size)
        for ((key, values) in metadata.enumValues) {
            dos.writeInt(strings.indexOf(key))
            dos.writeInt(values.size)
            for (v in values) writeAnyValue(dos, v, strings)
        }

        dos.writeInt(metadata.classOrigins.size)
        for ((className, source) in metadata.classOrigins) {
            dos.writeInt(strings.indexOf(className))
            dos.writeInt(strings.indexOf(source))
        }

        dos.writeInt(metadata.artifactDependencies.size)
        for ((fromArtifact, dependencies) in metadata.artifactDependencies) {
            dos.writeInt(strings.indexOf(fromArtifact))
            dos.writeInt(dependencies.size)
            for ((toArtifact, weight) in dependencies) {
                dos.writeInt(strings.indexOf(toArtifact))
                dos.writeInt(weight)
            }
        }

        // Member annotations
        dos.writeInt(metadata.memberAnnotations.size)
        for ((key, annotations) in metadata.memberAnnotations) {
            dos.writeInt(strings.indexOf(key))
            dos.writeInt(annotations.size)
            for ((fqn, attrs) in annotations) {
                dos.writeInt(strings.indexOf(fqn))
                dos.writeInt(attrs.size)
                for ((k, v) in attrs) {
                    dos.writeInt(strings.indexOf(k))
                    writeAnyValue(dos, v, strings)
                }
            }
        }

        // Branch scopes
        dos.writeInt(metadata.branchScopes.size)
        for (bs in metadata.branchScopes) {
            dos.writeInt(bs.conditionNodeId)
            writeMethodDescriptor(dos, bs.method, strings)
            dos.writeInt(bs.comparison.operator.ordinal)
            dos.writeInt(bs.comparison.comparandNodeId.value)
            dos.writeInt(bs.trueBranchNodeIds.size)
            for (id in bs.trueBranchNodeIds) dos.writeInt(id)
            dos.writeInt(bs.falseBranchNodeIds.size)
            for (id in bs.falseBranchNodeIds) dos.writeInt(id)
        }
    }

    /**
     * Append the synthetic identity section to `graph.metadata`, after the trailer: readers that
     * predate it stop at the trailer and never see it, the Rust reader ignores everything after the
     * last fixed section. Nothing is written when there is no identity to record, so a graph
     * without synthetic members persists byte-identically to one saved before the section existed.
     *
     * Layout: `int32 header = MAGIC_SYNTHETIC_IDENTITIES | SYNTHETIC_IDENTITIES_VERSION`,
     * `int32 count`, then per entry `int32 memberStringIndex` and [FINGERPRINT_BYTES] raw bytes.
     */
    fun writeSyntheticIdentities(metadata: GraphMetadata, dos: DataOutputStream, strings: StringTable) {
        if (metadata.syntheticIdentities.isEmpty()) return
        dos.writeInt(MAGIC_SYNTHETIC_IDENTITIES or SYNTHETIC_IDENTITIES_VERSION)
        dos.writeInt(metadata.syntheticIdentities.size)
        // Sorted so the file does not depend on the builder's map order.
        for ((member, fingerprint) in metadata.syntheticIdentities.toSortedMap()) {
            dos.writeInt(strings.indexOf(member))
            dos.write(decodeFingerprint(fingerprint))
        }
    }

    /**
     * Read the optional sections that follow the fixed metadata sections in any order: the
     * trailer that binds `graph.metadata` to its sidecar and the synthetic identities. Reading
     * stops at end of file or at the first header that is neither.
     */
    fun readMetadataOptionalSections(dis: DataInput, strings: StringTable, metadata: GraphMetadata): GraphMetadata {
        var digest: ByteArray? = null
        var identities: Map<String, String> = emptyMap()
        var header = readOptionalHeader(dis)
        while (header != null) {
            val magic = header and HEADER_MAGIC_MASK
            val version = header and BYTE_MASK
            // A section cut short by a truncated file is dropped as if it were absent: the fixed
            // sections and the node data are intact, so the graph stays readable without it.
            header = when {
                magic == MAGIC_METADATA_TRAILER && version == BRANCH_DEFINITIONS_VERSION -> {
                    digest = readTrailerDigest(dis)
                    if (digest == null) null else readOptionalHeader(dis)
                }
                magic == MAGIC_SYNTHETIC_IDENTITIES && version == SYNTHETIC_IDENTITIES_VERSION -> {
                    val loaded = readSyntheticIdentities(dis, strings)
                    if (loaded == null) null else {
                        identities = loaded
                        readOptionalHeader(dis)
                    }
                }
                else -> null
            }
        }
        return metadata.copy(branchDefinitionDigest = digest, syntheticIdentities = identities)
    }

    private fun readTrailerDigest(dis: DataInput): ByteArray? = try {
        ByteArray(DIGEST_BYTES).also(dis::readFully)
    } catch (_: EOFException) {
        null
    }

    private fun readOptionalHeader(dis: DataInput): Int? = try {
        dis.readInt()
    } catch (_: EOFException) {
        null
    }

    /**
     * The section's entries, or `null` when the file ends inside it or its count is not a count. The
     * entries are read in one block and decoded on first use: the metadata is loaded on the first
     * branch-scope access of a mapped graph, which a large corpus holds to a time budget, and a
     * corpus has tens of thousands of entries whose strings nothing may ever look up.
     */
    private fun readSyntheticIdentities(dis: DataInput, strings: StringTable): Map<String, String>? = try {
        val count = dis.readInt()
        if (count < 0 || count > MAX_SYNTHETIC_IDENTITIES) {
            null
        } else {
            LazySyntheticIdentities(count, ByteArray(count * SYNTHETIC_IDENTITY_ENTRY_BYTES).also(dis::readFully), strings)
        }
    } catch (_: EOFException) {
        null
    }

    /** One persisted entry: the member's string table index and the raw fingerprint. */
    private const val SYNTHETIC_IDENTITY_ENTRY_BYTES = Int.SIZE_BYTES + FINGERPRINT_BYTES

    /** More entries than any corpus has; a count above it is a corrupt section, not an allocation. */
    private const val MAX_SYNTHETIC_IDENTITIES = 1 shl 24

    private const val HEX_DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0xF

    internal fun encodeFingerprint(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and BYTE_MASK
            chars[index * 2] = HEX_DIGITS[value ushr NIBBLE_BITS]
            chars[index * 2 + 1] = HEX_DIGITS[value and NIBBLE_MASK]
        }
        return String(chars)
    }

    internal fun decodeFingerprint(hex: String): ByteArray {
        require(hex.length == FINGERPRINT_BYTES * 2) { "Expected ${FINGERPRINT_BYTES * 2} hex digits, got '$hex'" }
        return ByteArray(FINGERPRINT_BYTES) { index ->
            val high = Character.digit(hex[index * 2], HEX_RADIX)
            val low = Character.digit(hex[index * 2 + 1], HEX_RADIX)
            require(high >= 0 && low >= 0) { "Not a hex fingerprint: '$hex'" }
            ((high shl HEX_DIGIT_BITS) or low).toByte()
        }
    }

    fun loadMetadata(dis: DataInput, strings: StringTable): GraphMetadata {
        val formatVersion = readHeader(dis, MAGIC_METADATA)
        // Methods
        val methodCount = dis.readInt()
        val methods = mutableMapOf<String, MethodDescriptor>()
        repeat(methodCount) {
            val md = readMethodDescriptor(dis, strings)
            methods[md.signature] = md
        }

        // Type hierarchy: supertypes
        val superCount = dis.readInt()
        val supertypes = mutableMapOf<String, Set<TypeDescriptor>>()
        repeat(superCount) {
            val typeName = strings.get(dis.readInt())
            val count = dis.readInt()
            supertypes[typeName] = (0 until count).map { TypeDescriptor(strings.get(dis.readInt())) }.toSet()
        }

        // Type hierarchy: subtypes
        val subCount = dis.readInt()
        val subtypes = mutableMapOf<String, Set<TypeDescriptor>>()
        repeat(subCount) {
            val typeName = strings.get(dis.readInt())
            val count = dis.readInt()
            subtypes[typeName] = (0 until count).map { TypeDescriptor(strings.get(dis.readInt())) }.toSet()
        }

        // Enum values
        val enumCount = dis.readInt()
        val enumValues = mutableMapOf<String, List<Any?>>()
        repeat(enumCount) {
            val key = strings.get(dis.readInt())
            val count = dis.readInt()
            enumValues[key] = (0 until count).map { readAnyValue(dis, strings, formatVersion) }
        }

        val classOrigins = mutableMapOf<String, String>()
        if (formatVersion >= ARTIFACT_METADATA_FORMAT_VERSION) {
            val classOriginCount = dis.readInt()
            repeat(classOriginCount) {
                classOrigins[strings.get(dis.readInt())] = strings.get(dis.readInt())
            }
        }

        val artifactDependencies = mutableMapOf<String, Map<String, Int>>()
        if (formatVersion >= ARTIFACT_METADATA_FORMAT_VERSION) {
            val artifactCount = dis.readInt()
            repeat(artifactCount) {
                val fromArtifact = strings.get(dis.readInt())
                val dependencyCount = dis.readInt()
                val dependencies = mutableMapOf<String, Int>()
                repeat(dependencyCount) {
                    dependencies[strings.get(dis.readInt())] = dis.readInt()
                }
                artifactDependencies[fromArtifact] = dependencies
            }
        }

        // Member annotations
        val annCount = dis.readInt()
        val memberAnnotations = mutableMapOf<String, Map<String, Map<String, Any?>>>()
        repeat(annCount) {
            val key = strings.get(dis.readInt())
            val fqnCount = dis.readInt()
            val annotations = mutableMapOf<String, Map<String, Any?>>()
            repeat(fqnCount) {
                val fqn = strings.get(dis.readInt())
                val kvCount = dis.readInt()
                val kv = mutableMapOf<String, Any?>()
                repeat(kvCount) {
                    val k = strings.get(dis.readInt())
                    val v = readAnnotationValue(dis, strings, formatVersion)
                    kv[k] = v
                }
                annotations[fqn] = kv
            }
            memberAnnotations[key] = annotations
        }

        // Branch scopes
        val scopeCount = dis.readInt()
        val branchScopes = (0 until scopeCount).map {
            val condId = dis.readInt()
            val method = readMethodDescriptor(dis, strings)
            val op = ComparisonOp.entries[dis.readInt()]
            val comparandId = dis.readInt()
            val comparison = BranchComparison(op, NodeId(comparandId))
            val trueCount = dis.readInt()
            val trueIds = IntArray(trueCount) { dis.readInt() }
            val falseCount = dis.readInt()
            val falseIds = IntArray(falseCount) { dis.readInt() }
            BranchScopeData(condId, method, comparison, trueIds, falseIds)
        }

        return GraphMetadata(methods, supertypes, subtypes, enumValues, classOrigins, artifactDependencies, memberAnnotations, branchScopes)
    }

    fun readMetadataMethodCount(dis: DataInput): Int {
        readHeader(dis, MAGIC_METADATA)
        return dis.readInt()
    }

    // ========================================================================
    // Branch-side local definitions (optional `graph.branchdefs` sidecar)
    // ========================================================================

    /** Bytes before the payload: header, payload length, payload digest. */
    internal const val BRANCH_DEFINITIONS_PREAMBLE_BYTES: Int = Int.SIZE_BYTES + Int.SIZE_BYTES + DIGEST_BYTES

    /** Bytes of the `graph.metadata` trailer: magic and the sidecar payload digest. */
    internal const val METADATA_TRAILER_BYTES: Int = Int.SIZE_BYTES + DIGEST_BYTES

    /** [NodeTagLookup] result for an id that is not a persisted node. */
    const val NO_NODE = -1

    /** Largest sidecar payload a reader will allocate for; a declared length above it is corrupt. */
    internal const val MAX_BRANCH_DEFINITIONS_PAYLOAD_BYTES: Int = 512 * 1024 * 1024

    /**
     * Encode the sidecar for [branchScopes] (their per-side definitions, aligned by position
     * with the branch scopes in `graph.metadata`) and [localDefinitions] (every write of each
     * tracked local, keyed by local node id):
     *
     * ```
     * int32    header         = MAGIC_BRANCHDEFS | BRANCH_DEFINITIONS_VERSION
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
     * The returned [EncodedBranchDefinitions.payloadDigest] is what [writeMetadataTrailer]
     * stores in `graph.metadata`, binding the sidecar to the exact metadata written with it.
     */
    fun encodeBranchDefinitions(
        branchScopes: List<BranchScopeData>,
        localDefinitions: Map<Int, IntArray>
    ): EncodedBranchDefinitions {
        var payloadInts = 1L + 1L
        for (scope in branchScopes) payloadInts += 2L + scope.trueDefinitions.size + scope.falseDefinitions.size
        for (definitions in localDefinitions.values) payloadInts += 2L + definitions.size
        val payloadLength = payloadInts * Int.SIZE_BYTES
        require(payloadLength <= MAX_BRANCH_DEFINITIONS_PAYLOAD_BYTES) { "Branch definitions exceed the payload budget" }
        val buffer = ByteBuffer.allocate(BRANCH_DEFINITIONS_PREAMBLE_BYTES + payloadLength.toInt())
        buffer.position(BRANCH_DEFINITIONS_PREAMBLE_BYTES)
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
        val payloadDigest = sha256(bytes, BRANCH_DEFINITIONS_PREAMBLE_BYTES, payloadLength.toInt())
        buffer.position(0)
        buffer.putInt(MAGIC_BRANCHDEFS or BRANCH_DEFINITIONS_VERSION)
        buffer.putInt(payloadLength.toInt())
        buffer.put(payloadDigest)
        return EncodedBranchDefinitions(bytes, payloadDigest)
    }

    private fun putPackedDefinitions(buffer: ByteBuffer, packed: IntArray) {
        buffer.putInt(packed.size / BranchScope.DEFINITION_STRIDE)
        for (value in packed) buffer.putInt(value)
    }

    /** Append the sidecar binding to `graph.metadata`: readers that predate it stop before the trailer. */
    fun writeMetadataTrailer(dos: DataOutputStream, payloadDigest: ByteArray) {
        require(payloadDigest.size == DIGEST_BYTES) { "Expected a SHA-256 digest, got ${payloadDigest.size} bytes" }
        dos.writeInt(MAGIC_METADATA_TRAILER or BRANCH_DEFINITIONS_VERSION)
        dos.write(payloadDigest)
    }

    /**
     * Read the trailer that [writeMetadataTrailer] appends after the metadata sections, or `null`
     * when the file ends there (written before the sidecar existed, or re-saved by such a writer).
     */
    fun readMetadataTrailer(dis: DataInput): ByteArray? = try {
        val header = dis.readInt()
        if (header and HEADER_MAGIC_MASK == MAGIC_METADATA_TRAILER && header and BYTE_MASK == BRANCH_DEFINITIONS_VERSION) {
            ByteArray(DIGEST_BYTES).also(dis::readFully)
        } else {
            null
        }
    } catch (_: EOFException) {
        null
    }

    /**
     * Decode a sidecar's preamble: the header must match, the declared payload length must equal
     * [payloadBytesInFile] and stay within [maxPayloadBytes], and the payload digest must be the one
     * `graph.metadata` binds to ([expectedPayloadDigest]). Nothing beyond the preamble has been read
     * when this returns, so a corrupt file never drives an allocation.
     */
    fun decodeBranchDefinitionPreamble(
        preamble: ByteArray,
        payloadBytesInFile: Long,
        expectedPayloadDigest: ByteArray?,
        maxPayloadBytes: Int = MAX_BRANCH_DEFINITIONS_PAYLOAD_BYTES
    ): BranchDefinitionPreamble {
        if (preamble.size < BRANCH_DEFINITIONS_PREAMBLE_BYTES) return BranchDefinitionPreamble.rejected("is corrupt (too short)")
        val buffer = ByteBuffer.wrap(preamble)
        val header = buffer.getInt()
        val payloadLength = buffer.getInt()
        val payloadDigest = ByteArray(DIGEST_BYTES).also(buffer::get)
        val rejection = when {
            header and HEADER_MAGIC_MASK != MAGIC_BRANCHDEFS || header and BYTE_MASK != BRANCH_DEFINITIONS_VERSION ->
                "does not match (unknown header 0x${header.toString(HEX_RADIX)})"
            payloadLength < 0 || payloadLength > maxPayloadBytes ->
                "is corrupt (declared payload $payloadLength bytes exceeds the $maxPayloadBytes-byte budget)"
            payloadLength.toLong() != payloadBytesInFile ->
                "is corrupt (declared payload $payloadLength bytes, file has $payloadBytesInFile)"
            expectedPayloadDigest == null -> "does not match (graph.metadata carries no branch-definition digest)"
            !payloadDigest.contentEquals(expectedPayloadDigest) ->
                "does not match the definitions graph.metadata was written with"
            else -> null
        }
        return if (rejection == null) {
            BranchDefinitionPreamble(payloadLength, payloadDigest, null)
        } else {
            BranchDefinitionPreamble.rejected(rejection)
        }
    }

    /** Resolves a node id to the persisted [tagOf] tag of that node, or [NO_NODE] when the graph has no such node. */
    fun interface NodeTagLookup {
        fun tagOf(nodeId: Int): Int
    }

    /**
     * Decode a payload whose length and digest [decodeBranchDefinitionPreamble] accepted. The bytes
     * must hash to [expectedPayloadDigest], the scope count must equal [expectedScopeCount], every
     * count is checked against the bytes that remain before an array is allocated, the local table
     * may hold at most [nodeCount] distinct locals (so its decoded size is bounded by the graph, not
     * by the payload), and the payload must end exactly after the local tables. The content is then
     * held to the writer's invariants against the persisted nodes ([nodeTags]): every table key is a
     * `LocalVariable` node, every table entry names its key with strictly increasing ordinals, every
     * constant id is [BranchScope.NO_CONSTANT] or a constant node, every side definition appears in
     * the table of its local, and every table belongs to a local some side defines. A loaded graph
     * therefore never exposes a definition that points outside it or a table that disagrees with the
     * side definitions a consumer subtracts from it.
     */
    fun decodeBranchDefinitionPayload(
        payload: ByteArray,
        expectedScopeCount: Int,
        expectedPayloadDigest: ByteArray,
        nodeCount: Int,
        nodeTags: NodeTagLookup
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
        MessageDigest.getInstance(DIGEST_ALGORITHM).also { it.update(bytes, offset, length) }.digest()

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
     * Checks the decoded definitions against the persisted nodes and the writer's invariants. Node
     * lookups are made once per table key and once per distinct constant id, not once per triple.
     */
    private class DefinitionValidator(private val nodeTags: NodeTagLookup) {
        private val constants = IntOpenHashSet()

        fun isLocal(nodeId: Int): Boolean = nodeTags.tagOf(nodeId) == TAG_LOCAL_VARIABLE

        private fun isConstant(nodeId: Int): Boolean {
            if (nodeId == BranchScope.NO_CONSTANT || constants.contains(nodeId)) return true
            val tag = nodeTags.tagOf(nodeId)
            return (tag in TAG_INT_CONSTANT..TAG_ENUM_CONSTANT) && constants.add(nodeId)
        }

        /** One branch side: ordinals strictly increasing, constants nodes; the locals are checked against the tables. */
        fun readSide(buffer: ByteBuffer): IntArray = readEntries(buffer, NO_NODE)

        /** One local's table: every entry names [localId], ordinals strictly increase, constants are nodes. */
        fun readTable(buffer: ByteBuffer, localId: Int): IntArray = readEntries(buffer, localId)

        private fun readEntries(buffer: ByteBuffer, tableLocal: Int): IntArray {
            val packed = readPacked(buffer)
            val where = if (tableLocal == NO_NODE) "a branch side" else "the table of local $tableLocal"
            var base = 0
            var previousOrdinal = -1
            while (base < packed.size) {
                val ordinal = packed[base]
                require(ordinal >= 0 && (tableLocal == NO_NODE || packed[base + 1] == tableLocal) && isConstant(packed[base + 2])) {
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

    // ========================================================================
    // ControlFlowEdge comparison writing / reading
    // ========================================================================

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

    // ========================================================================
    // Helpers (string-table-aware)
    // ========================================================================

    private fun writeMethodDescriptor(dos: DataOutputStream, md: MethodDescriptor, strings: StringTable) {
        dos.writeInt(strings.indexOf(md.declaringClass.className))
        dos.writeInt(strings.indexOf(md.name))
        dos.writeInt(md.parameterTypes.size)
        for (p in md.parameterTypes) dos.writeInt(strings.indexOf(p.className))
        dos.writeInt(strings.indexOf(md.returnType.className))
    }

    private fun readMethodDescriptor(dis: DataInput, strings: StringTable): MethodDescriptor {
        val className = TypeDescriptor(strings.get(dis.readInt()))
        val name = strings.get(dis.readInt())
        val paramCount = dis.readInt()
        val params = (0 until paramCount).map { TypeDescriptor(strings.get(dis.readInt())) }
        val returnType = TypeDescriptor(strings.get(dis.readInt()))
        return MethodDescriptor(className, name, params, returnType)
    }

    private fun writeAnyValue(dos: DataOutputStream, value: Any?, strings: StringTable) {
        when (value) {
            is Int -> { dos.writeByte(VAL_INT); dos.writeInt(value) }
            is Long -> { dos.writeByte(VAL_LONG); dos.writeLong(value) }
            is String -> { dos.writeByte(VAL_STRING); dos.writeInt(strings.indexOf(value)) }
            is Float -> { dos.writeByte(VAL_FLOAT); dos.writeFloat(value) }
            is Double -> { dos.writeByte(VAL_DOUBLE); dos.writeDouble(value) }
            is Boolean -> { dos.writeByte(VAL_BOOLEAN); dos.writeBoolean(value) }
            null -> { dos.writeByte(VAL_NULL) }
            is EnumValueReference -> { dos.writeByte(VAL_ENUM_REF); dos.writeInt(strings.indexOf(value.enumClass)); dos.writeInt(strings.indexOf(value.enumName)) }
            is List<*> -> {
                dos.writeByte(VAL_LIST)
                dos.writeInt(value.size)
                value.forEach { writeAnyValue(dos, it, strings) }
            }
            else -> { dos.writeByte(VAL_STRING); dos.writeInt(strings.indexOf(value.toString())) }
        }
    }

    private fun readAnyValue(dis: DataInput, strings: StringTable, formatVersion: Int): Any? = when (dis.readByte().toInt()) {
        VAL_INT -> dis.readInt()
        VAL_LONG -> dis.readLong()
        VAL_STRING -> strings.get(dis.readInt())
        VAL_FLOAT -> dis.readFloat()
        VAL_DOUBLE -> dis.readDouble()
        VAL_BOOLEAN -> dis.readBoolean()
        VAL_NULL -> null
        VAL_ENUM_REF -> EnumValueReference(strings.get(dis.readInt()), strings.get(dis.readInt()))
        VAL_LIST -> {
            require(formatVersion >= TRANSITIONAL_FORMAT_VERSION) {
                "Encountered list value in GraphStore format version $formatVersion. Re-save the graph with a current Graphite build."
            }
            List(dis.readInt()) { readAnyValue(dis, strings, formatVersion) }
        }
        else -> strings.get(dis.readInt()) // fallback
    }

    private fun readAnnotationValue(dis: DataInput, strings: StringTable, formatVersion: Int): Any? {
        return if (formatVersion == LEGACY_FORMAT_VERSION) {
            strings.get(dis.readInt()).let { it.ifEmpty { null } }
        } else {
            readAnyValue(dis, strings, formatVersion)
        }
    }
}

/**
 * Holds all non-graph metadata that doesn't fit in the adjacency structure.
 */
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
    val syntheticIdentities: Map<String, String> = emptyMap()
)

/**
 * The synthetic identity section as read: [records] holds `count` records of a string table index and a raw
 * fingerprint, decoded into a map on first use (see [NodeSerializer.readMetadataOptionalSections]).
 */
internal class LazySyntheticIdentities(
    private val count: Int,
    private val records: ByteArray,
    private val strings: StringTable
) : AbstractMap<String, String>() {
    private val decoded: Map<String, String> by lazy {
        val buffer = ByteBuffer.wrap(records)
        val fingerprint = ByteArray(NodeSerializer.FINGERPRINT_BYTES)
        val map = LinkedHashMap<String, String>(count * 2)
        repeat(count) {
            val member = strings.get(buffer.getInt())
            buffer.get(fingerprint)
            map[member] = NodeSerializer.encodeFingerprint(fingerprint)
        }
        map
    }

    override val entries: Set<Map.Entry<String, String>> get() = decoded.entries
    override val size: Int get() = count
    override fun get(key: String): String? = decoded[key]
    override fun containsKey(key: String): Boolean = decoded.containsKey(key)
}

/** A sidecar as [NodeSerializer.encodeBranchDefinitions] produces it, with the digest `graph.metadata` binds to. */
class EncodedBranchDefinitions(val bytes: ByteArray, val payloadDigest: ByteArray)

/** Outcome of [NodeSerializer.decodeBranchDefinitionPreamble]: the accepted length and digest, or the [rejection]. */
data class BranchDefinitionPreamble(val payloadLength: Int, val payloadDigest: ByteArray?, val rejection: String?) {
    companion object {
        fun rejected(reason: String) = BranchDefinitionPreamble(0, null, reason)
    }
}

/**
 * The `graph.branchdefs` sidecar as loaded: one `(true, false)` packed pair per metadata branch scope, and
 * per-local tables held in an unboxed map whose size the decoder bounds by the graph's node count.
 */
data class PersistedBranchDefinitions(
    val scopes: List<Pair<IntArray, IntArray>>,
    val locals: Map<Int, IntArray>
) {
    companion object {
        val EMPTY = PersistedBranchDefinitions(emptyList(), emptyMap())
    }
}

/** Outcome of [NodeSerializer.decodeBranchDefinitionPayload]: either [definitions] or the [rejection] reason. */
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
    fun toBranchScope(definitions: Pair<IntArray, IntArray>? = null): BranchScope = BranchScope(
        conditionNodeId = NodeId(conditionNodeId),
        method = method,
        comparison = comparison,
        trueBranchNodeIds = IntOpenHashSet(trueBranchNodeIds),
        falseBranchNodeIds = IntOpenHashSet(falseBranchNodeIds),
        trueDefinitions = BranchScope.unpackDefinitions(definitions?.first ?: trueDefinitions),
        falseDefinitions = BranchScope.unpackDefinitions(definitions?.second ?: falseDefinitions)
    )

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
