package io.johnsonlee.graphite.graph

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
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.ResourceAccessor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

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

    private val nodeStream = workDir.resolve(NODES_FILE).toFile().outputStream().buffered()
    private val edgeStream = workDir.resolve(EDGES_FILE).toFile().outputStream().buffered()

    private var nodeCount = 0
    private var edgeCount = 0L

    // Small metadata (kept in memory -- typically <1% of total data)
    private val methods = linkedSetOf<MethodDescriptor>()
    private val nodeMethodIds = IdentityHashMap<MethodDescriptor, Int>()
    private val nodeMethods = mutableListOf<MethodDescriptor>()
    private val nodeTypeIds = mutableMapOf<String, Int>()
    private val nodeTypes = mutableListOf<TypeDescriptor>()
    private val typeHierarchyBuilder = TypeHierarchy.Builder()
    private val enumValues = mutableMapOf<String, List<Any?>>()
    private val classOrigins = mutableMapOf<String, String>()
    private val artifactDependencies = mutableMapOf<String, MutableMap<String, Int>>()
    private val memberAnnotations = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
    private val branchScopes = mutableListOf<DefaultGraph.RawBranchScope>()
    private var resourceAccessor: ResourceAccessor = EmptyResourceAccessor

    private val nodeDos = DataOutputStream(nodeStream)
    private val edgeDos = DataOutputStream(edgeStream)
    private var nodeBuffer = ReusableByteArrayOutputStream(NODE_SERIALIZATION_BUFFER_BYTES)
    private var nodeBufferDos = DataOutputStream(nodeBuffer)

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

    override fun addClassOrigin(className: String, source: String): FullGraphBuilder {
        classOrigins.putIfAbsent(className, source)
        return this
    }

    override fun addArtifactDependency(fromArtifact: String, toArtifact: String, weight: Int): FullGraphBuilder {
        if (fromArtifact.isBlank() || toArtifact.isBlank() || fromArtifact == toArtifact || weight <= 0) return this
        artifactDependencies
            .getOrPut(fromArtifact) { mutableMapOf() }
            .merge(toArtifact, weight, Int::plus)
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

        val nodeFile = workDir.resolve(NODES_FILE).toFile()
        val nodeFileLen = nodeFile.length()
        val (maxNodeId, nodeTypeCounts) = scanNodeIndexShape(nodeFile, nodeFileLen)
        val nodeIndex = if (maxNodeId >= 0) LongArray(maxNodeId + 1) { -1L } else LongArray(0)
        val nodeTypeArrays = nodeTypeCounts.mapValues { (_, count) -> IntArray(count) }
        val nodeTypeCursors = HashMap<Class<out Node>, Int>(nodeTypeArrays.size)

        var offset = 0L
        DataInputStream(nodeFile.inputStream().buffered()).use { dis ->
            while (offset < nodeFileLen) {
                val len = dis.readInt()
                val nodeId = dis.readInt()
                val tag = dis.readByte().toInt()
                skipFully(dis, len - NODE_ID_BYTES - NODE_TAG_BYTES)
                nodeIndex[nodeId] = offset
                val nodeClass = nodeClassForTag(tag)
                val cursor = nodeTypeCursors[nodeClass] ?: 0
                nodeTypeArrays.getValue(nodeClass)[cursor] = nodeId
                nodeTypeCursors[nodeClass] = cursor + 1
                offset += LENGTH_PREFIX_BYTES + len
            }
        }
        val nodeTypeIndex = nodeTypeArrays
        val nodeCapacity = nodeIndex.size
        require(edgeCount <= Int.MAX_VALUE.toLong()) {
            "MmapGraphBuilder supports at most ${Int.MAX_VALUE} edges in memory-mapped indexes, got $edgeCount"
        }
        val totalEdges = edgeCount.toInt()
        val outgoingCounts = IntArray(nodeCapacity)

        offset = 0L
        val edgeFile = workDir.resolve(EDGES_FILE).toFile()
        val edgeFileLen = edgeFile.length()
        DataInputStream(edgeFile.inputStream().buffered()).use { edgeDis ->
            while (offset < edgeFileLen) {
                val from = edgeDis.readInt()
                edgeDis.readInt()
                outgoingCounts[from]++
                offset += EDGE_ENDPOINT_BYTES + skipEdgePayload(edgeDis)
            }
        }

        val outgoingStarts = buildPrefixStarts(outgoingCounts)
        val outgoingOffsetIndex = buildOutgoingEdgeOffsetIndex(edgeFile, edgeFileLen, totalEdges, outgoingStarts, outgoingCounts)

        val methodIndex = LinkedHashMap<String, MethodDescriptor>(methods.size)
        methods.forEach { methodIndex[it.signature] = it }

        return MmapGraph(
            dataDir = workDir,
            nodeIndex = nodeIndex,
            nodeTypeIndex = nodeTypeIndex,
            outgoingIndex = MmapGraph.EdgeOffsetIndex(outgoingStarts, outgoingOffsetIndex),
            nodeMethods = nodeMethods.toList(),
            nodeTypes = nodeTypes.toList(),
            methodIndex = methodIndex,
            typeHierarchy = typeHierarchyBuilder.build(),
            enumValuesMap = enumValues.toMap(),
            classOriginsMap = classOrigins.toMap(),
            artifactDependenciesMap = artifactDependencies.mapValues { (_, deps) -> deps.toMap() },
            memberAnnotationsMap = memberAnnotations.mapValues { it.value.toMap() },
            branchScopeData = branchScopes.toList(),
            incomingIndex = null,
            resources = resourceAccessor
        )
    }

    private fun buildOutgoingEdgeOffsetIndex(
        edgeFile: File,
        edgeFileLen: Long,
        totalEdges: Int,
        outgoingStarts: IntArray,
        outgoingCounts: IntArray
    ): MmapGraph.LongOffsetIndex {
        if (totalEdges == 0) {
            return MmapGraph.HeapLongOffsetIndex(LongArray(0))
        }

        val outgoingOffsetsFile = workDir.resolve(OUTGOING_OFFSETS_FILE)
        System.arraycopy(outgoingStarts, 0, outgoingCounts, 0, outgoingCounts.size)
        MmapGraph.createMappedLongOffsetIndex(outgoingOffsetsFile, totalEdges).use { outgoingOffsets ->
            var offset = 0L
            DataInputStream(edgeFile.inputStream().buffered()).use { edgeDis ->
                while (offset < edgeFileLen) {
                    val recordOffset = offset
                    val from = edgeDis.readInt()
                    edgeDis.readInt()
                    offset += EDGE_ENDPOINT_BYTES + skipEdgePayload(edgeDis)
                    outgoingOffsets.putLong(outgoingCounts[from]++, recordOffset)
                }
            }
        }
        return MmapGraph.MappedLongOffsetIndex(outgoingOffsetsFile)
    }

    companion object {
        private const val NODES_FILE = "nodes.dat"
        private const val EDGES_FILE = "edges.dat"
        private const val OUTGOING_OFFSETS_FILE = "outgoing-offsets.dat"
        private const val LENGTH_PREFIX_BYTES = 4
        private const val NODE_ID_BYTES = 4
        private const val NODE_TAG_BYTES = 1
        private const val EDGE_ENDPOINT_BYTES = 8
        private const val EDGE_TAG_BYTES = 1
        private const val EDGE_KIND_BYTES = 1
        private const val EDGE_FLAGS_BYTES = 1
        private const val CONTROL_FLOW_HAS_COMPARISON_BYTES = 1
        private const val COMPARISON_OPERATOR_BYTES = 1
        private const val COMPARISON_NODE_ID_BYTES = 4
        private const val NODE_SERIALIZATION_BUFFER_BYTES = 128
        private const val MAX_REUSABLE_NODE_BUFFER_BYTES = 1_048_576

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

        private val NODE_CLASSES_BY_TAG: Array<Class<out Node>> = arrayOf(
            IntConstant::class.java,
            StringConstant::class.java,
            LongConstant::class.java,
            FloatConstant::class.java,
            DoubleConstant::class.java,
            BooleanConstant::class.java,
            NullConstant::class.java,
            EnumConstant::class.java,
            LocalVariable::class.java,
            FieldNode::class.java,
            ParameterNode::class.java,
            ReturnNode::class.java,
            CallSiteNode::class.java,
            AnnotationNode::class.java,
            ResourceValueNode::class.java,
            ResourceFileNode::class.java
        )

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
        internal fun deserializeNode(bytes: ByteArray, nodeMethods: List<MethodDescriptor>): Node =
            deserializeNode(DataInputStream(ByteArrayInputStream(bytes)), nodeMethods, emptyList())

        @Suppress("CyclomaticComplexMethod")
        internal fun deserializeNode(
            s: DataInput,
            nodeMethods: List<MethodDescriptor>,
            nodeTypes: List<TypeDescriptor>
        ): Node {
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
                    val enumType = readTypeDescriptor(s, nodeTypes)
                    val enumName = readString(s)
                    val argCount = s.readInt()
                    val args = (0 until argCount).map { readAnyValue(s, nodeTypes) }
                    EnumConstant(id, enumType, enumName, args)
                }
                TAG_LOCAL_VARIABLE -> LocalVariable(
                    id,
                    readString(s),
                    readTypeDescriptor(s, nodeTypes),
                    readMethodDescriptor(s, nodeMethods)
                )
                TAG_FIELD_NODE -> FieldNode(
                    id,
                    FieldDescriptor(readTypeDescriptor(s, nodeTypes), readString(s), readTypeDescriptor(s, nodeTypes)),
                    s.readBoolean()
                )
                TAG_PARAMETER_NODE -> ParameterNode(id, s.readInt(), readTypeDescriptor(s, nodeTypes), readMethodDescriptor(s, nodeMethods))
                TAG_RETURN_NODE -> {
                    val method = readMethodDescriptor(s, nodeMethods)
                    val hasActual = s.readBoolean()
                    ReturnNode(id, method, if (hasActual) readTypeDescriptor(s, nodeTypes) else null)
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
                    val value = readAnyValue(s, nodeTypes)
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
                        val v = readAnyValue(s, nodeTypes)
                        values[k] = v
                    }
                    AnnotationNode(id, name, className, memberName, values)
                }
                else -> error("Unknown node type tag: $tag")
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
                else -> error("Unknown edge type tag: $tag")
            }
        }

        private fun nodeClassForTag(tag: Int): Class<out Node> {
            require(tag in NODE_CLASSES_BY_TAG.indices) {
                "Unknown node type tag during index build: $tag"
            }
            return NODE_CLASSES_BY_TAG[tag]
        }

        private fun skipFully(input: DataInputStream, bytes: Int) {
            require(bytes >= 0) { "Cannot skip a negative byte count: $bytes" }
            var remaining = bytes
            while (remaining > 0) {
                val skipped = input.skipBytes(remaining)
                if (skipped <= 0) {
                    if (input.read() == -1) {
                        throw EOFException("Unexpected EOF while skipping node payload")
                    }
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }

        /**
         * Skip past the payload of an edge record (after from/to have been read).
         *
         * @return number of bytes consumed after the 8-byte from/to endpoint header.
         */
        private fun skipEdgePayload(input: DataInput): Int {
            return when (input.readByte().toInt()) {
                TAG_EDGE_DATAFLOW -> {
                    input.readByte()   // kind
                    EDGE_TAG_BYTES + EDGE_KIND_BYTES
                }
                TAG_EDGE_CALL -> {
                    input.readByte()   // flags
                    EDGE_TAG_BYTES + EDGE_FLAGS_BYTES
                }
                TAG_EDGE_TYPE -> {
                    input.readByte()   // kind
                    EDGE_TAG_BYTES + EDGE_KIND_BYTES
                }
                TAG_EDGE_CONTROL_FLOW -> {
                    input.readByte()                  // kind
                    var consumed = EDGE_TAG_BYTES + EDGE_KIND_BYTES + CONTROL_FLOW_HAS_COMPARISON_BYTES
                    val hasComparison = input.readByte().toInt() == 1
                    if (hasComparison) {
                        input.readByte()              // operator
                        input.readInt()               // comparandNodeId
                        consumed += COMPARISON_OPERATOR_BYTES + COMPARISON_NODE_ID_BYTES
                    }
                    consumed
                }
                TAG_EDGE_RESOURCE -> {
                    input.readByte()   // kind
                    EDGE_TAG_BYTES + EDGE_KIND_BYTES
                }
                else -> error("Unknown edge type tag during skip")
            }
        }

        internal fun readMethodDescriptor(dis: DataInput, nodeMethods: List<MethodDescriptor>): MethodDescriptor {
            val methodId = dis.readInt()
            return nodeMethods[methodId]
        }

        internal fun readAnyValue(dis: DataInput, nodeTypes: List<TypeDescriptor>): Any? = when (dis.readByte().toInt()) {
            VAL_INT -> dis.readInt()
            VAL_LONG -> dis.readLong()
            VAL_STRING -> readString(dis)
            VAL_FLOAT -> dis.readFloat()
            VAL_DOUBLE -> dis.readDouble()
            VAL_BOOLEAN -> dis.readBoolean()
            VAL_NULL -> null
            VAL_ENUM_REF -> EnumValueReference(readString(dis), readString(dis))
            VAL_LIST -> List(dis.readInt()) { readAnyValue(dis, nodeTypes) }
            else -> readString(dis)
        }

        internal fun readTypeDescriptor(input: DataInput, nodeTypes: List<TypeDescriptor>): TypeDescriptor {
            return if (nodeTypes.isEmpty()) {
                TypeDescriptor(readString(input))
            } else {
                nodeTypes[input.readInt()]
            }
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

        private fun scanNodeIndexShape(nodeFile: File, fileLen: Long): Pair<Int, Map<Class<out Node>, Int>> {
            var maxNodeId = -1
            val nodeTypeCounts = HashMap<Class<out Node>, Int>()
            var offset = 0L
            DataInputStream(nodeFile.inputStream().buffered()).use { dis ->
                while (offset < fileLen) {
                    val len = dis.readInt()
                    val nodeId = dis.readInt()
                    val tag = dis.readByte().toInt()
                    skipFully(dis, len - NODE_ID_BYTES - NODE_TAG_BYTES)
                    maxNodeId = maxOf(maxNodeId, nodeId)
                    nodeTypeCounts.merge(nodeClassForTag(tag), 1, Int::plus)
                    offset += LENGTH_PREFIX_BYTES + len
                }
            }
            return maxNodeId to nodeTypeCounts
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
        val baos = nodeBuffer
        val dos = nodeBufferDos
        baos.reset()

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
                writeTypeDescriptor(dos, node.enumType)
                writeString(dos, node.enumName)
                dos.writeInt(node.constructorArgs.size)
                node.constructorArgs.forEach { writeAnyValue(dos, it) }
            }
            is LocalVariable -> {
                dos.writeByte(TAG_LOCAL_VARIABLE)
                writeString(dos, node.name)
                writeTypeDescriptor(dos, node.type)
                writeMethodDescriptor(dos, node.method)
            }
            is FieldNode -> {
                dos.writeByte(TAG_FIELD_NODE)
                writeTypeDescriptor(dos, node.descriptor.declaringClass)
                writeString(dos, node.descriptor.name)
                writeTypeDescriptor(dos, node.descriptor.type)
                dos.writeBoolean(node.isStatic)
            }
            is ParameterNode -> {
                dos.writeByte(TAG_PARAMETER_NODE)
                dos.writeInt(node.index)
                writeTypeDescriptor(dos, node.type)
                writeMethodDescriptor(dos, node.method)
            }
            is ReturnNode -> {
                dos.writeByte(TAG_RETURN_NODE)
                writeMethodDescriptor(dos, node.method)
                dos.writeBoolean(node.actualType != null)
                if (node.actualType != null) writeTypeDescriptor(dos, node.actualType)
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
        out.writeInt(baos.size())
        baos.writeTo(out)

        if (baos.capacity > MAX_REUSABLE_NODE_BUFFER_BYTES) {
            nodeBuffer = ReusableByteArrayOutputStream(NODE_SERIALIZATION_BUFFER_BYTES)
            nodeBufferDos = DataOutputStream(nodeBuffer)
        }
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

    private fun writeTypeDescriptor(dos: DataOutput, type: TypeDescriptor) {
        dos.writeInt(ensureNodeTypeId(type))
    }

    private fun ensureNodeMethodId(method: MethodDescriptor): Int {
        return nodeMethodIds.getOrPut(method) {
            val id = nodeMethods.size
            nodeMethods += method
            id
        }
    }

    private fun ensureNodeTypeId(type: TypeDescriptor): Int {
        val className = type.className
        return nodeTypeIds.getOrPut(className) {
            val id = nodeTypes.size
            nodeTypes += TypeDescriptor(className)
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

    private class ReusableByteArrayOutputStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
        val capacity: Int
            get() = buf.size
    }
}
