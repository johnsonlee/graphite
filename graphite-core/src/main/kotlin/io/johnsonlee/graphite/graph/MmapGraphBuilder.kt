package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.ResourceAccessor
import java.io.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * A graph builder that writes nodes and edges to disk-backed files,
 * avoiding heap accumulation for large graphs.
 *
 * Nodes and edges are appended to temporary files as they are added.
 * [build] creates an [MmapGraph] that reads data on demand from disk,
 * keeping only lightweight indexes in memory.
 *
 * **Memory profile during construction:** O(index structures) -- the node
 * and edge payloads live on disk, not on the JVM heap.  Only small metadata
 * (methods, type hierarchy, annotations, enum values, branch scopes) is
 * kept in memory because it is typically less than 1% of total data.
 *
 * **Memory profile after build:** Only node/edge indexes are in heap
 * (~10 bytes per node, ~16 bytes per edge).  Actual node/edge data is
 * read from disk on demand via [MmapGraph].
 *
 * @param workDir directory for data files; defaults to a fresh
 *   temp directory.  The directory is NOT deleted after [build] because
 *   [MmapGraph] continues to read from it.
 */
class MmapGraphBuilder(
    internal val workDir: Path = Files.createTempDirectory("graphite-mmap")
) : FullGraphBuilder {

    private val nodeStream = workDir.resolve("nodes.dat").toFile().outputStream().buffered()
    private val edgeStream = workDir.resolve("edges.dat").toFile().outputStream().buffered()

    private var nodeCount = 0
    private var edgeCount = 0L

    // Small metadata (kept in memory -- typically <1% of total data)
    private val methods = linkedSetOf<MethodDescriptor>()
    private val nodeMethodIds = mutableMapOf<MethodDescriptor, Int>()
    private val nodeMethods = mutableListOf<MethodDescriptor>()
    private val typeHierarchyBuilder = TypeHierarchy.Builder()
    private val enumValues = mutableMapOf<String, List<Any?>>()
    private val memberAnnotations = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
    private val branchScopes = mutableListOf<DefaultGraph.RawBranchScope>()
    private var resourceAccessor: ResourceAccessor = EmptyResourceAccessor

    private val nodeDos = DataOutputStream(nodeStream)
    private val edgeDos = DataOutputStream(edgeStream)

    override fun addNode(node: Node): FullGraphBuilder {
        writeNode(nodeDos, node)
        nodeCount++
        return this
    }

    override fun addEdge(edge: Edge): FullGraphBuilder {
        writeEdge(edgeDos, edge)
        edgeCount++
        return this
    }

    override fun addMethod(method: MethodDescriptor): FullGraphBuilder {
        methods.add(method)
        ensureNodeMethodId(method)
        return this
    }

    override fun addTypeRelation(subtype: TypeDescriptor, supertype: TypeDescriptor, relation: TypeRelation): FullGraphBuilder {
        typeHierarchyBuilder.addRelation(subtype, supertype, relation)
        return this
    }

    override fun addEnumValues(enumClass: String, enumName: String, values: List<Any?>): FullGraphBuilder {
        enumValues["$enumClass#$enumName"] = values
        return this
    }

    override fun addMemberAnnotation(
        className: String,
        memberName: String,
        annotationFqn: String,
        values: Map<String, Any?>
    ): FullGraphBuilder {
        memberAnnotations.getOrPut("$className#$memberName") { mutableMapOf() }[annotationFqn] = values
        return this
    }

    override fun addBranchScope(
        conditionNodeId: NodeId,
        method: MethodDescriptor,
        comparison: BranchComparison,
        trueBranchNodeIds: IntArray,
        falseBranchNodeIds: IntArray
    ): FullGraphBuilder {
        branchScopes.add(
            DefaultGraph.RawBranchScope(
                conditionNodeId = conditionNodeId.value,
                method = method,
                comparison = comparison,
                trueBranchNodeIds = trueBranchNodeIds,
                falseBranchNodeIds = falseBranchNodeIds
            )
        )
        return this
    }

    override fun setResources(resources: ResourceAccessor): FullGraphBuilder {
        this.resourceAccessor = resources
        return this
    }

    override fun build(): Graph {
        nodeDos.flush()
        edgeDos.flush()
        nodeStream.close()
        edgeStream.close()

        // Build indexes by scanning files sequentially
        var nodeOffsets = LongArray(16) { -1L }
        var maxNodeId = -1
        val nodeTypeIndexBuilder = HashMap<Class<out Node>, MutableList<Int>>()

        var offset = 0L
        DataInputStream(workDir.resolve("nodes.dat").toFile().inputStream().buffered()).use { dis ->
            val fileLen = workDir.resolve("nodes.dat").toFile().length()
            while (offset < fileLen) {
                val len = dis.readInt()
                val bytes = ByteArray(len)
                dis.readFully(bytes)
                val node = deserializeNode(bytes, nodeMethods)
                val nodeId = node.id.value
                if (nodeId >= nodeOffsets.size) {
                    nodeOffsets = growLongArray(nodeOffsets, nodeId + 1)
                }
                nodeOffsets[nodeId] = offset
                maxNodeId = maxOf(maxNodeId, nodeId)
                nodeTypeIndexBuilder.getOrPut(node::class.java) { mutableListOf() }.add(nodeId)
                offset += 4 + len // 4 bytes for length prefix + payload
            }
        }

        val nodeIndex = if (maxNodeId >= 0) nodeOffsets.copyOf(maxNodeId + 1) else LongArray(0)
        val nodeTypeIndex = nodeTypeIndexBuilder.mapValues { (_, ids) -> ids.toIntArray() }
        val nodeCapacity = nodeIndex.size
        require(edgeCount <= Int.MAX_VALUE.toLong()) {
            "MmapGraphBuilder supports at most ${Int.MAX_VALUE} edges in memory-mapped indexes, got $edgeCount"
        }
        val totalEdges = edgeCount.toInt()
        val outgoingCounts = IntArray(nodeCapacity)
        val incomingCounts = IntArray(nodeCapacity)

        offset = 0L
        RandomAccessFile(workDir.resolve("edges.dat").toFile(), "r").use { edgeRaf ->
            while (edgeRaf.filePointer < edgeRaf.length()) {
                val from = edgeRaf.readInt()
                val to = edgeRaf.readInt()
                outgoingCounts[from]++
                incomingCounts[to]++
                skipEdgePayload(edgeRaf)
            }
        }

        val outgoingStarts = buildPrefixStarts(outgoingCounts)
        val incomingStarts = buildPrefixStarts(incomingCounts)
        val outgoingOffsets = LongArray(totalEdges)
        val incomingOffsets = LongArray(totalEdges)
        val outgoingCursor = outgoingStarts.copyOf(outgoingStarts.size - 1)
        val incomingCursor = incomingStarts.copyOf(incomingStarts.size - 1)

        RandomAccessFile(workDir.resolve("edges.dat").toFile(), "r").use { edgeRaf ->
            while (edgeRaf.filePointer < edgeRaf.length()) {
                offset = edgeRaf.filePointer
                val from = edgeRaf.readInt()
                val to = edgeRaf.readInt()
                skipEdgePayload(edgeRaf)
                outgoingOffsets[outgoingCursor[from]++] = offset
                incomingOffsets[incomingCursor[to]++] = offset
            }
        }

        val methodIndex = LinkedHashMap<String, MethodDescriptor>(methods.size)
        methods.forEach { methodIndex[it.signature] = it }

        return MmapGraph(
            dataDir = workDir,
            nodeIndex = nodeIndex,
            nodeTypeIndex = nodeTypeIndex,
            outgoingIndex = MmapGraph.EdgeOffsetIndex(outgoingStarts, outgoingOffsets),
            incomingIndex = MmapGraph.EdgeOffsetIndex(incomingStarts, incomingOffsets),
            nodeMethods = nodeMethods.toList(),
            methodIndex = methodIndex,
            typeHierarchy = typeHierarchyBuilder.build(),
            enumValuesMap = enumValues.toMap(),
            memberAnnotationsMap = memberAnnotations.mapValues { it.value.toMap() },
            branchScopeData = branchScopes.toList(),
            resources = resourceAccessor
        )
    }

    companion object {
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

        // Edge type tags
        internal const val TAG_EDGE_DATAFLOW = 0
        internal const val TAG_EDGE_CALL = 1
        internal const val TAG_EDGE_TYPE = 2
        internal const val TAG_EDGE_CONTROL_FLOW = 3
        internal const val TAG_EDGE_RESOURCE = 4

        // Value type tags (for Any? serialization)
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
         * Deserialize a node from its length-prefixed bytes (excluding the 4-byte length prefix).
         */
        internal fun deserializeNode(bytes: ByteArray, nodeMethods: List<MethodDescriptor>): Node {
            val s = DataInputStream(ByteArrayInputStream(bytes))
            val id = NodeId(s.readInt())
            return when (val tag = s.readByte().toInt()) {
                TAG_INT_CONSTANT -> IntConstant(id, s.readInt())
                TAG_STRING_CONSTANT -> StringConstant(id, readString(s))
                TAG_LONG_CONSTANT -> LongConstant(id, s.readLong())
                TAG_FLOAT_CONSTANT -> FloatConstant(id, s.readFloat())
                TAG_DOUBLE_CONSTANT -> DoubleConstant(id, s.readDouble())
                TAG_BOOLEAN_CONSTANT -> BooleanConstant(id, s.readBoolean())
                TAG_NULL_CONSTANT -> NullConstant(id)
                TAG_ENUM_CONSTANT -> {
                    val enumType = TypeDescriptor(readString(s))
                    val enumName = readString(s)
                    val argCount = s.readInt()
                    val args = (0 until argCount).map { readAnyValue(s) }
                    EnumConstant(id, enumType, enumName, args)
                }
                TAG_LOCAL_VARIABLE -> LocalVariable(id, readString(s), TypeDescriptor(readString(s)), readMethodDescriptor(s, nodeMethods))
                TAG_FIELD_NODE -> FieldNode(
                    id,
                    FieldDescriptor(TypeDescriptor(readString(s)), readString(s), TypeDescriptor(readString(s))),
                    s.readBoolean()
                )
                TAG_PARAMETER_NODE -> ParameterNode(id, s.readInt(), TypeDescriptor(readString(s)), readMethodDescriptor(s, nodeMethods))
                TAG_RETURN_NODE -> {
                    val method = readMethodDescriptor(s, nodeMethods)
                    val hasActual = s.readBoolean()
                    ReturnNode(id, method, if (hasActual) TypeDescriptor(readString(s)) else null)
                }
                TAG_RESOURCE_FILE_NODE -> {
                    val path = readString(s)
                    val source = readString(s)
                    val format = readString(s)
                    val profile = if (s.readBoolean()) readString(s) else null
                    ResourceFileNode(id, path, source, format, profile)
                }
                TAG_RESOURCE_VALUE_NODE -> {
                    val path = readString(s)
                    val key = readString(s)
                    val value = readAnyValue(s)
                    val format = readString(s)
                    val profile = if (s.readBoolean()) readString(s) else null
                    ResourceValueNode(id, path, key, value, format, profile)
                }
                TAG_CALL_SITE_NODE -> {
                    val caller = readMethodDescriptor(s, nodeMethods)
                    val callee = readMethodDescriptor(s, nodeMethods)
                    val line = s.readInt().let { if (it == -1) null else it }
                    val receiver = s.readInt().let { if (it == -1) null else NodeId(it) }
                    val argCount = s.readInt()
                    val args = (0 until argCount).map { NodeId(s.readInt()) }
                    CallSiteNode(id, caller, callee, line, receiver, args)
                }
                TAG_ANNOTATION_NODE -> {
                    val name = readString(s)
                    val className = readString(s)
                    val memberName = readString(s)
                    val kvCount = s.readInt()
                    val values = mutableMapOf<String, Any?>()
                    repeat(kvCount) {
                        val k = readString(s)
                        val v = readAnyValue(s)
                        values[k] = v
                    }
                    AnnotationNode(id, name, className, memberName, values)
                }
                else -> throw IllegalStateException("Unknown node type tag: $tag")
            }
        }

        /**
         * Deserialize an edge from a [RandomAccessFile] at its current position.
         * The file pointer is advanced past the entire edge record.
         */
        internal fun deserializeEdge(input: java.io.DataInput): Edge {
            val from = NodeId(input.readInt())
            val to = NodeId(input.readInt())
            return when (val tag = input.readByte().toInt()) {
                TAG_EDGE_DATAFLOW -> DataFlowEdge(from, to, DataFlowKind.entries[input.readByte().toInt()])
                TAG_EDGE_CALL -> {
                    val flags = input.readByte().toInt()
                    CallEdge(from, to, isVirtual = (flags and 1) != 0, isDynamic = (flags and 2) != 0)
                }
                TAG_EDGE_TYPE -> TypeEdge(from, to, TypeRelation.entries[input.readByte().toInt()])
                TAG_EDGE_CONTROL_FLOW -> {
                    val kind = ControlFlowKind.entries[input.readByte().toInt()]
                    val hasComparison = input.readByte().toInt() == 1
                    val comparison = if (hasComparison) {
                        BranchComparison(ComparisonOp.entries[input.readByte().toInt()], NodeId(input.readInt()))
                    } else {
                        null
                    }
                    ControlFlowEdge(from, to, kind, comparison)
                }
                TAG_EDGE_RESOURCE -> ResourceEdge(from, to, ResourceRelation.entries[input.readByte().toInt()])
                else -> throw IllegalStateException("Unknown edge type tag: $tag")
            }
        }

        /**
         * Skip past the payload of an edge record (after from/to have been read).
         * Advances the file pointer past type tag + type-specific bytes.
         */
        private fun skipEdgePayload(raf: RandomAccessFile) {
            when (raf.readByte().toInt()) {
                TAG_EDGE_DATAFLOW -> raf.readByte()   // kind
                TAG_EDGE_CALL -> raf.readByte()       // flags
                TAG_EDGE_TYPE -> raf.readByte()       // kind
                TAG_EDGE_CONTROL_FLOW -> {
                    raf.readByte()                    // kind
                    val hasComparison = raf.readByte().toInt() == 1
                    if (hasComparison) {
                        raf.readByte()                // operator
                        raf.readInt()                 // comparandNodeId
                    }
                }
                TAG_EDGE_RESOURCE -> raf.readByte()   // kind
                else -> throw IllegalStateException("Unknown edge type tag during skip")
            }
        }

        internal fun readMethodDescriptor(dis: DataInput, nodeMethods: List<MethodDescriptor>): MethodDescriptor {
            val methodId = dis.readInt()
            return nodeMethods[methodId]
        }

        internal fun readAnyValue(dis: DataInput): Any? = when (dis.readByte().toInt()) {
            VAL_INT -> dis.readInt()
            VAL_LONG -> dis.readLong()
            VAL_STRING -> readString(dis)
            VAL_FLOAT -> dis.readFloat()
            VAL_DOUBLE -> dis.readDouble()
            VAL_BOOLEAN -> dis.readBoolean()
            VAL_NULL -> null
            VAL_ENUM_REF -> EnumValueReference(readString(dis), readString(dis))
            VAL_LIST -> List(dis.readInt()) { readAnyValue(dis) }
            else -> readString(dis)
        }

        internal fun writeString(out: DataOutput, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        internal fun readString(input: DataInput): String {
            val size = input.readInt()
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun growLongArray(current: LongArray, minSize: Int): LongArray {
            var newSize = current.size
            while (newSize < minSize) {
                newSize = maxOf(newSize * 2, 1)
            }
            return LongArray(newSize) { index -> if (index < current.size) current[index] else -1L }
        }

        private fun buildPrefixStarts(counts: IntArray): IntArray {
            val starts = IntArray(counts.size + 1)
            for (i in counts.indices) {
                starts[i + 1] = starts[i] + counts[i]
            }
            return starts
        }
    }

    // ========================================================================
    // Instance serialization methods (write to RandomAccessFile during build)
    // ========================================================================

    private fun writeNode(out: DataOutputStream, node: Node) {
        val baos = ByteArrayOutputStream(128)
            val dos = DataOutputStream(baos)
            dos.writeInt(node.id.value)
            when (node) {
                is IntConstant -> { dos.writeByte(TAG_INT_CONSTANT); dos.writeInt(node.value) }
                is StringConstant -> { dos.writeByte(TAG_STRING_CONSTANT); writeString(dos, node.value) }
                is LongConstant -> { dos.writeByte(TAG_LONG_CONSTANT); dos.writeLong(node.value) }
                is FloatConstant -> { dos.writeByte(TAG_FLOAT_CONSTANT); dos.writeFloat(node.value) }
                is DoubleConstant -> { dos.writeByte(TAG_DOUBLE_CONSTANT); dos.writeDouble(node.value) }
                is BooleanConstant -> { dos.writeByte(TAG_BOOLEAN_CONSTANT); dos.writeBoolean(node.value) }
                is NullConstant -> { dos.writeByte(TAG_NULL_CONSTANT) }
                is EnumConstant -> {
                    dos.writeByte(TAG_ENUM_CONSTANT)
                    writeString(dos, node.enumType.className)
                    writeString(dos, node.enumName)
                    dos.writeInt(node.constructorArgs.size)
                    node.constructorArgs.forEach { writeAnyValue(dos, it) }
                }
                is LocalVariable -> {
                    dos.writeByte(TAG_LOCAL_VARIABLE)
                    writeString(dos, node.name)
                    writeString(dos, node.type.className)
                    writeMethodDescriptor(dos, node.method)
                }
                is FieldNode -> {
                    dos.writeByte(TAG_FIELD_NODE)
                    writeString(dos, node.descriptor.declaringClass.className)
                    writeString(dos, node.descriptor.name)
                    writeString(dos, node.descriptor.type.className)
                    dos.writeBoolean(node.isStatic)
                }
                is ParameterNode -> {
                    dos.writeByte(TAG_PARAMETER_NODE)
                    dos.writeInt(node.index)
                    writeString(dos, node.type.className)
                    writeMethodDescriptor(dos, node.method)
                }
                is ReturnNode -> {
                    dos.writeByte(TAG_RETURN_NODE)
                    writeMethodDescriptor(dos, node.method)
                    dos.writeBoolean(node.actualType != null)
                    if (node.actualType != null) writeString(dos, node.actualType.className)
                }
                is ResourceFileNode -> {
                    dos.writeByte(TAG_RESOURCE_FILE_NODE)
                    writeString(dos, node.path)
                    writeString(dos, node.source)
                    writeString(dos, node.format)
                    dos.writeBoolean(node.profile != null)
                    if (node.profile != null) writeString(dos, node.profile)
                }
                is ResourceValueNode -> {
                    dos.writeByte(TAG_RESOURCE_VALUE_NODE)
                    writeString(dos, node.path)
                    writeString(dos, node.key)
                    writeAnyValue(dos, node.value)
                    writeString(dos, node.format)
                    dos.writeBoolean(node.profile != null)
                    if (node.profile != null) writeString(dos, node.profile)
                }
                is CallSiteNode -> {
                    dos.writeByte(TAG_CALL_SITE_NODE)
                    writeMethodDescriptor(dos, node.caller)
                writeMethodDescriptor(dos, node.callee)
                dos.writeInt(node.lineNumber ?: -1)
                dos.writeInt(node.receiver?.value ?: -1)
                dos.writeInt(node.arguments.size)
                node.arguments.forEach { dos.writeInt(it.value) }
                }
                is AnnotationNode -> {
                    dos.writeByte(TAG_ANNOTATION_NODE)
                    writeString(dos, node.name)
                    writeString(dos, node.className)
                    writeString(dos, node.memberName)
                    dos.writeInt(node.values.size)
                    for ((k, v) in node.values) {
                        writeString(dos, k)
                        writeAnyValue(dos, v)
                    }
                }
            }
        dos.flush()
        val bytes = baos.toByteArray()
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun writeEdge(out: DataOutputStream, edge: Edge) {
        out.writeInt(edge.from.value)
        out.writeInt(edge.to.value)
        when (edge) {
            is DataFlowEdge -> {
                out.writeByte(TAG_EDGE_DATAFLOW)
                out.writeByte(edge.kind.ordinal)
            }
            is CallEdge -> {
                out.writeByte(TAG_EDGE_CALL)
                out.writeByte((if (edge.isVirtual) 1 else 0) or (if (edge.isDynamic) 2 else 0))
            }
            is TypeEdge -> {
                out.writeByte(TAG_EDGE_TYPE)
                out.writeByte(edge.kind.ordinal)
            }
            is ControlFlowEdge -> {
                out.writeByte(TAG_EDGE_CONTROL_FLOW)
                out.writeByte(edge.kind.ordinal)
                if (edge.comparison != null) {
                    out.writeByte(1)
                    out.writeByte(edge.comparison.operator.ordinal)
                    out.writeInt(edge.comparison.comparandNodeId.value)
                } else {
                    out.writeByte(0)
                }
            }
            is ResourceEdge -> {
                out.writeByte(TAG_EDGE_RESOURCE)
                out.writeByte(edge.kind.ordinal)
            }
        }
    }

    private fun writeMethodDescriptor(dos: DataOutput, md: MethodDescriptor) {
        dos.writeInt(ensureNodeMethodId(md))
    }

    private fun ensureNodeMethodId(method: MethodDescriptor): Int {
        return nodeMethodIds.getOrPut(method) {
            val id = nodeMethods.size
            nodeMethods += method
            id
        }
    }

    private fun writeAnyValue(dos: DataOutput, value: Any?) {
        when (value) {
            is Int -> { dos.writeByte(VAL_INT); dos.writeInt(value) }
            is Long -> { dos.writeByte(VAL_LONG); dos.writeLong(value) }
            is String -> { dos.writeByte(VAL_STRING); writeString(dos, value) }
            is Float -> { dos.writeByte(VAL_FLOAT); dos.writeFloat(value) }
            is Double -> { dos.writeByte(VAL_DOUBLE); dos.writeDouble(value) }
            is Boolean -> { dos.writeByte(VAL_BOOLEAN); dos.writeBoolean(value) }
            null -> { dos.writeByte(VAL_NULL) }
            is EnumValueReference -> {
                dos.writeByte(VAL_ENUM_REF)
                writeString(dos, value.enumClass)
                writeString(dos, value.enumName)
            }
            is List<*> -> {
                dos.writeByte(VAL_LIST)
                dos.writeInt(value.size)
                value.forEach { writeAnyValue(dos, it) }
            }
            else -> { dos.writeByte(VAL_STRING); writeString(dos, value.toString()) }
        }
    }
}
