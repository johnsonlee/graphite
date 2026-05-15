package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import java.io.*

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
    fun readHeader(dis: DataInputStream, expectedMagic: Int): Int {
        val h = dis.readInt()
        val prefix = h and 0xFFFFFF00.toInt()
        require(prefix == expectedMagic) {
            "Invalid file magic: expected 0x${expectedMagic.toString(16)}, got 0x${prefix.toString(16)}"
        }
        val version = h and 0xFF
        validateVersion(version, expectedMagic)
        return version
    }

    /** Overload for [RandomAccessFile]. */
    fun readHeader(raf: RandomAccessFile, expectedMagic: Int): Int {
        val h = raf.readInt()
        val prefix = h and 0xFFFFFF00.toInt()
        require(prefix == expectedMagic) {
            "Invalid file magic: expected 0x${expectedMagic.toString(16)}, got 0x${prefix.toString(16)}"
        }
        val version = h and 0xFF
        validateVersion(version, expectedMagic)
        return version
    }

    private fun validateVersion(version: Int, expectedMagic: Int) {
        require(
            version == LEGACY_FORMAT_VERSION ||
                version == TRANSITIONAL_FORMAT_VERSION ||
                version == FORMAT_VERSION
        ) {
            "Unsupported GraphStore format version $version for 0x${expectedMagic.toString(16)}. " +
                "This build supports versions $LEGACY_FORMAT_VERSION, $TRANSITIONAL_FORMAT_VERSION and $FORMAT_VERSION."
        }
    }

    // Node type tags
    private const val TAG_INT_CONSTANT = 0
    private const val TAG_STRING_CONSTANT = 1
    private const val TAG_LONG_CONSTANT = 2
    private const val TAG_FLOAT_CONSTANT = 3
    private const val TAG_DOUBLE_CONSTANT = 4
    private const val TAG_BOOLEAN_CONSTANT = 5
    private const val TAG_NULL_CONSTANT = 6
    private const val TAG_ENUM_CONSTANT = 7
    private const val TAG_LOCAL_VARIABLE = 8
    private const val TAG_FIELD_NODE = 9
    private const val TAG_PARAMETER_NODE = 10
    private const val TAG_RETURN_NODE = 11
    private const val TAG_CALL_SITE_NODE = 12
    private const val TAG_ANNOTATION_NODE = 13
    private const val TAG_RESOURCE_VALUE_NODE = 14
    private const val TAG_RESOURCE_FILE_NODE = 15

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
        is DataFlowEdge -> 0 or (edge.kind.ordinal shl 3)
        is CallEdge -> 1 or
                ((if (edge.isVirtual) 1 else 0) shl 3) or
                ((if (edge.isDynamic) 1 else 0) shl 4)
        is TypeEdge -> 2 or (edge.kind.ordinal shl 3)
        is ControlFlowEdge -> 3 or (edge.kind.ordinal shl 3)
        is ResourceEdge -> 4 or (edge.kind.ordinal shl 3)
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
        val family = label and 0x3
        return when (family) {
            0 -> DataFlowEdge(from, to, DataFlowKind.entries[(label shr 2) and 0xF])
            1 -> CallEdge(
                from, to,
                isVirtual = ((label shr 6) and 1) == 1,
                isDynamic = ((label shr 7) and 1) == 1
            )
            2 -> TypeEdge(from, to, TypeRelation.entries[(label shr 2) and 0xF])
            3 -> ControlFlowEdge(from, to, ControlFlowKind.entries[(label shr 2) and 0xF], comparison)
            else -> throw IllegalArgumentException("Unknown edge family: $family")
        }
    }

    private fun decodeEdgeV3(label: Int, from: NodeId, to: NodeId, comparison: BranchComparison?): Edge {
        val family = label and 0x7
        return when (family) {
            0 -> DataFlowEdge(from, to, DataFlowKind.entries[(label shr 3) and 0xF])
            1 -> CallEdge(
                from, to,
                isVirtual = ((label shr 3) and 1) == 1,
                isDynamic = ((label shr 4) and 1) == 1
            )
            2 -> TypeEdge(from, to, TypeRelation.entries[(label shr 3) and 0xF])
            3 -> ControlFlowEdge(from, to, ControlFlowKind.entries[(label shr 3) and 0xF], comparison)
            4 -> ResourceEdge(from, to, ResourceRelation.entries[(label shr 3) and 0xF])
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

    fun writeNode(dos: DataOutputStream, node: Node, strings: StringTable): Int {
        dos.writeInt(node.id.value)
        val tag = when (node) {
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

    fun readNode(dis: DataInputStream, strings: StringTable, formatVersion: Int = FORMAT_VERSION): Node {
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

    fun loadMetadata(dis: DataInputStream, strings: StringTable): GraphMetadata {
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

    fun readComparisons(dis: DataInputStream): Map<Long, BranchComparison> {
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

    private fun readMethodDescriptor(dis: DataInputStream, strings: StringTable): MethodDescriptor {
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

    private fun readAnyValue(dis: DataInputStream, strings: StringTable, formatVersion: Int): Any? = when (dis.readByte().toInt()) {
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

    private fun readAnnotationValue(dis: DataInputStream, strings: StringTable, formatVersion: Int): Any? {
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
    val branchScopes: List<BranchScopeData>
)

data class BranchScopeData(
    val conditionNodeId: Int,
    val method: MethodDescriptor,
    val comparison: BranchComparison,
    val trueBranchNodeIds: IntArray,
    val falseBranchNodeIds: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BranchScopeData) return false
        return conditionNodeId == other.conditionNodeId &&
                method == other.method &&
                comparison == other.comparison &&
                trueBranchNodeIds.contentEquals(other.trueBranchNodeIds) &&
                falseBranchNodeIds.contentEquals(other.falseBranchNodeIds)
    }

    override fun hashCode(): Int {
        var result = conditionNodeId
        result = 31 * result + method.hashCode()
        result = 31 * result + comparison.hashCode()
        result = 31 * result + trueBranchNodeIds.contentHashCode()
        result = 31 * result + falseBranchNodeIds.contentHashCode()
        return result
    }
}
