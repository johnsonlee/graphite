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

    private val nodeFile = RandomAccessFile(workDir.resolve("nodes.dat").toFile(), "rw")
    private val edgeFile = RandomAccessFile(workDir.resolve("edges.dat").toFile(), "rw")

    private var nodeCount = 0
    private var edgeCount = 0L

    // Small metadata (kept in memory -- typically <1% of total data)
    private val methods = mutableMapOf<String, MethodDescriptor>()
    private val typeHierarchyBuilder = TypeHierarchy.Builder()
    private val enumValues = mutableMapOf<String, List<Any?>>()
    private val memberAnnotations = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
    private val branchScopes = mutableListOf<DefaultGraph.RawBranchScope>()
    private var resourceAccessor: ResourceAccessor = EmptyResourceAccessor

    override fun addNode(node: Node): FullGraphBuilder {
        writeNode(nodeFile, node)
        nodeCount++
        return this
    }

    override fun addEdge(edge: Edge): FullGraphBuilder {
        writeEdge(edgeFile, edge)
        edgeCount++
        return this
    }

    override fun addMethod(method: MethodDescriptor): FullGraphBuilder {
        methods[method.signature] = method
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
        nodeFile.close()
        edgeFile.close()

        // Build indexes by scanning files with RandomAccessFile (gives us file position)
        val nodeIndex = HashMap<Int, Long>()
        val nodeTypeIndex = HashMap<Class<out Node>, MutableList<Int>>()

        val nodeRaf = RandomAccessFile(workDir.resolve("nodes.dat").toFile(), "r")
        while (nodeRaf.filePointer < nodeRaf.length()) {
            val offset = nodeRaf.filePointer
            val len = nodeRaf.readInt()
            val bytes = ByteArray(len)
            nodeRaf.readFully(bytes)
            val node = deserializeNode(bytes)
            nodeIndex[node.id.value] = offset
            nodeTypeIndex.getOrPut(node::class.java) { mutableListOf() }.add(node.id.value)
        }
        nodeRaf.close()

        val outgoingIndex = HashMap<Int, MutableList<Long>>()
        val incomingIndex = HashMap<Int, MutableList<Long>>()

        val edgeRaf = RandomAccessFile(workDir.resolve("edges.dat").toFile(), "r")
        while (edgeRaf.filePointer < edgeRaf.length()) {
            val offset = edgeRaf.filePointer
            val from = edgeRaf.readInt()
            val to = edgeRaf.readInt()
            // Read and skip the rest of the edge to advance the file pointer
            skipEdgePayload(edgeRaf)
            outgoingIndex.getOrPut(from) { mutableListOf() }.add(offset)
            incomingIndex.getOrPut(to) { mutableListOf() }.add(offset)
        }
        edgeRaf.close()

        return MmapGraph(
            dataDir = workDir,
            nodeIndex = nodeIndex,
            nodeTypeIndex = nodeTypeIndex,
            outgoingIndex = outgoingIndex,
            incomingIndex = incomingIndex,
            methodIndex = methods.toMap(),
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

        // Edge type tags
        internal const val TAG_EDGE_DATAFLOW = 0
        internal const val TAG_EDGE_CALL = 1
        internal const val TAG_EDGE_TYPE = 2
        internal const val TAG_EDGE_CONTROL_FLOW = 3

        // Value type tags (for Any? serialization)
        internal const val VAL_INT = 0
        internal const val VAL_LONG = 1
        internal const val VAL_STRING = 2
        internal const val VAL_FLOAT = 3
        internal const val VAL_DOUBLE = 4
        internal const val VAL_BOOLEAN = 5
        internal const val VAL_NULL = 6
        internal const val VAL_ENUM_REF = 7

        /**
         * Deserialize a node from its length-prefixed bytes (excluding the 4-byte length prefix).
         */
        internal fun deserializeNode(bytes: ByteArray): Node {
            val s = DataInputStream(ByteArrayInputStream(bytes))
            val id = NodeId(s.readInt())
            return when (val tag = s.readByte().toInt()) {
                TAG_INT_CONSTANT -> IntConstant(id, s.readInt())
                TAG_STRING_CONSTANT -> StringConstant(id, s.readUTF())
                TAG_LONG_CONSTANT -> LongConstant(id, s.readLong())
                TAG_FLOAT_CONSTANT -> FloatConstant(id, s.readFloat())
                TAG_DOUBLE_CONSTANT -> DoubleConstant(id, s.readDouble())
                TAG_BOOLEAN_CONSTANT -> BooleanConstant(id, s.readBoolean())
                TAG_NULL_CONSTANT -> NullConstant(id)
                TAG_ENUM_CONSTANT -> {
                    val enumType = TypeDescriptor(s.readUTF())
                    val enumName = s.readUTF()
                    val argCount = s.readInt()
                    val args = (0 until argCount).map { readAnyValue(s) }
                    EnumConstant(id, enumType, enumName, args)
                }
                TAG_LOCAL_VARIABLE -> LocalVariable(id, s.readUTF(), TypeDescriptor(s.readUTF()), readMethodDescriptor(s))
                TAG_FIELD_NODE -> FieldNode(
                    id,
                    FieldDescriptor(TypeDescriptor(s.readUTF()), s.readUTF(), TypeDescriptor(s.readUTF())),
                    s.readBoolean()
                )
                TAG_PARAMETER_NODE -> ParameterNode(id, s.readInt(), TypeDescriptor(s.readUTF()), readMethodDescriptor(s))
                TAG_RETURN_NODE -> {
                    val method = readMethodDescriptor(s)
                    val hasActual = s.readBoolean()
                    ReturnNode(id, method, if (hasActual) TypeDescriptor(s.readUTF()) else null)
                }
                TAG_CALL_SITE_NODE -> {
                    val caller = readMethodDescriptor(s)
                    val callee = readMethodDescriptor(s)
                    val line = s.readInt().let { if (it == -1) null else it }
                    val receiver = s.readInt().let { if (it == -1) null else NodeId(it) }
                    val argCount = s.readInt()
                    val args = (0 until argCount).map { NodeId(s.readInt()) }
                    CallSiteNode(id, caller, callee, line, receiver, args)
                }
                else -> throw IllegalStateException("Unknown node type tag: $tag")
            }
        }

        /**
         * Deserialize an edge from a [RandomAccessFile] at its current position.
         * The file pointer is advanced past the entire edge record.
         */
        internal fun deserializeEdge(raf: RandomAccessFile): Edge {
            val from = NodeId(raf.readInt())
            val to = NodeId(raf.readInt())
            return when (val tag = raf.readByte().toInt()) {
                TAG_EDGE_DATAFLOW -> DataFlowEdge(from, to, DataFlowKind.entries[raf.readByte().toInt()])
                TAG_EDGE_CALL -> {
                    val flags = raf.readByte().toInt()
                    CallEdge(from, to, isVirtual = (flags and 1) != 0, isDynamic = (flags and 2) != 0)
                }
                TAG_EDGE_TYPE -> TypeEdge(from, to, TypeRelation.entries[raf.readByte().toInt()])
                TAG_EDGE_CONTROL_FLOW -> {
                    val kind = ControlFlowKind.entries[raf.readByte().toInt()]
                    val hasComparison = raf.readByte().toInt() == 1
                    val comparison = if (hasComparison) {
                        BranchComparison(ComparisonOp.entries[raf.readByte().toInt()], NodeId(raf.readInt()))
                    } else {
                        null
                    }
                    ControlFlowEdge(from, to, kind, comparison)
                }
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
                else -> throw IllegalStateException("Unknown edge type tag during skip")
            }
        }

        internal fun readMethodDescriptor(dis: DataInputStream): MethodDescriptor {
            val cls = TypeDescriptor(dis.readUTF())
            val name = dis.readUTF()
            val paramCount = dis.readInt()
            val params = (0 until paramCount).map { TypeDescriptor(dis.readUTF()) }
            val returnType = TypeDescriptor(dis.readUTF())
            return MethodDescriptor(cls, name, params, returnType)
        }

        internal fun readAnyValue(dis: DataInputStream): Any? = when (dis.readByte().toInt()) {
            VAL_INT -> dis.readInt()
            VAL_LONG -> dis.readLong()
            VAL_STRING -> dis.readUTF()
            VAL_FLOAT -> dis.readFloat()
            VAL_DOUBLE -> dis.readDouble()
            VAL_BOOLEAN -> dis.readBoolean()
            VAL_NULL -> null
            VAL_ENUM_REF -> EnumValueReference(dis.readUTF(), dis.readUTF())
            else -> dis.readUTF()
        }
    }

    // ========================================================================
    // Instance serialization methods (write to RandomAccessFile during build)
    // ========================================================================

    private fun writeNode(raf: RandomAccessFile, node: Node) {
        val baos = ByteArrayOutputStream(128)
        val dos = DataOutputStream(baos)
        dos.writeInt(node.id.value)
        when (node) {
            is IntConstant -> { dos.writeByte(TAG_INT_CONSTANT); dos.writeInt(node.value) }
            is StringConstant -> { dos.writeByte(TAG_STRING_CONSTANT); dos.writeUTF(node.value) }
            is LongConstant -> { dos.writeByte(TAG_LONG_CONSTANT); dos.writeLong(node.value) }
            is FloatConstant -> { dos.writeByte(TAG_FLOAT_CONSTANT); dos.writeFloat(node.value) }
            is DoubleConstant -> { dos.writeByte(TAG_DOUBLE_CONSTANT); dos.writeDouble(node.value) }
            is BooleanConstant -> { dos.writeByte(TAG_BOOLEAN_CONSTANT); dos.writeBoolean(node.value) }
            is NullConstant -> { dos.writeByte(TAG_NULL_CONSTANT) }
            is EnumConstant -> {
                dos.writeByte(TAG_ENUM_CONSTANT)
                dos.writeUTF(node.enumType.className)
                dos.writeUTF(node.enumName)
                dos.writeInt(node.constructorArgs.size)
                node.constructorArgs.forEach { writeAnyValue(dos, it) }
            }
            is LocalVariable -> {
                dos.writeByte(TAG_LOCAL_VARIABLE)
                dos.writeUTF(node.name)
                dos.writeUTF(node.type.className)
                writeMethodDescriptor(dos, node.method)
            }
            is FieldNode -> {
                dos.writeByte(TAG_FIELD_NODE)
                dos.writeUTF(node.descriptor.declaringClass.className)
                dos.writeUTF(node.descriptor.name)
                dos.writeUTF(node.descriptor.type.className)
                dos.writeBoolean(node.isStatic)
            }
            is ParameterNode -> {
                dos.writeByte(TAG_PARAMETER_NODE)
                dos.writeInt(node.index)
                dos.writeUTF(node.type.className)
                writeMethodDescriptor(dos, node.method)
            }
            is ReturnNode -> {
                dos.writeByte(TAG_RETURN_NODE)
                writeMethodDescriptor(dos, node.method)
                dos.writeBoolean(node.actualType != null)
                if (node.actualType != null) dos.writeUTF(node.actualType.className)
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
        }
        dos.flush()
        val bytes = baos.toByteArray()
        raf.writeInt(bytes.size)
        raf.write(bytes)
    }

    private fun writeEdge(raf: RandomAccessFile, edge: Edge) {
        raf.writeInt(edge.from.value)
        raf.writeInt(edge.to.value)
        when (edge) {
            is DataFlowEdge -> {
                raf.writeByte(TAG_EDGE_DATAFLOW)
                raf.writeByte(edge.kind.ordinal)
            }
            is CallEdge -> {
                raf.writeByte(TAG_EDGE_CALL)
                raf.writeByte((if (edge.isVirtual) 1 else 0) or (if (edge.isDynamic) 2 else 0))
            }
            is TypeEdge -> {
                raf.writeByte(TAG_EDGE_TYPE)
                raf.writeByte(edge.kind.ordinal)
            }
            is ControlFlowEdge -> {
                raf.writeByte(TAG_EDGE_CONTROL_FLOW)
                raf.writeByte(edge.kind.ordinal)
                if (edge.comparison != null) {
                    raf.writeByte(1)
                    raf.writeByte(edge.comparison.operator.ordinal)
                    raf.writeInt(edge.comparison.comparandNodeId.value)
                } else {
                    raf.writeByte(0)
                }
            }
        }
    }

    private fun writeMethodDescriptor(dos: DataOutputStream, md: MethodDescriptor) {
        dos.writeUTF(md.declaringClass.className)
        dos.writeUTF(md.name)
        dos.writeInt(md.parameterTypes.size)
        md.parameterTypes.forEach { dos.writeUTF(it.className) }
        dos.writeUTF(md.returnType.className)
    }

    private fun writeAnyValue(dos: DataOutputStream, value: Any?) {
        when (value) {
            is Int -> { dos.writeByte(VAL_INT); dos.writeInt(value) }
            is Long -> { dos.writeByte(VAL_LONG); dos.writeLong(value) }
            is String -> { dos.writeByte(VAL_STRING); dos.writeUTF(value) }
            is Float -> { dos.writeByte(VAL_FLOAT); dos.writeFloat(value) }
            is Double -> { dos.writeByte(VAL_DOUBLE); dos.writeDouble(value) }
            is Boolean -> { dos.writeByte(VAL_BOOLEAN); dos.writeBoolean(value) }
            null -> { dos.writeByte(VAL_NULL) }
            is EnumValueReference -> { dos.writeByte(VAL_ENUM_REF); dos.writeUTF(value.enumClass); dos.writeUTF(value.enumName) }
            else -> { dos.writeByte(VAL_STRING); dos.writeUTF(value.toString()) }
        }
    }
}
