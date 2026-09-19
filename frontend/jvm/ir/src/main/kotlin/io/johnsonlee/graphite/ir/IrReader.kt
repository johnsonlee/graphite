package io.johnsonlee.graphite.ir

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
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
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.ir.v1.Chunk
import io.johnsonlee.graphite.ir.v1.Header
import io.johnsonlee.graphite.ir.v1.MethodRef
import io.johnsonlee.graphite.ir.v1.TypeRef
import io.johnsonlee.graphite.ir.v1.Value
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** The schema this reader understands; a header with another version is refused. */
const val IR_SCHEMA_VERSION = 1

/** What a stream declared about itself, returned beside the graph. */
data class IrSummary(
    val header: Header,
    val nodeCount: Long,
    val edgeCount: Long,
    val stringCount: Long
)

/** A malformed or truncated IR stream. */
class IrFormatException(message: String) : RuntimeException(message)

/**
 * Reads a Graph IR stream (see `ir/graphite_ir.proto`) into a [Graph] through a
 * [FullGraphBuilder], so an imported graph is the same object the JVM frontend builds
 * and persists the same way.
 *
 * IR node ids are dense from 0; graph node ids start at 1 (`NodeId.next()` never yields
 * 0), so every IR id is shifted by one on the way in.
 */
class IrReader(private val builder: FullGraphBuilder = DefaultGraph.Builder()) {

    private val strings = ArrayList<String>()
    private val declared = HashSet<Int>()
    private val referenced = HashSet<Int>()
    private var header: Header? = null
    private var nodeCount = 0L
    private var edgeCount = 0L

    fun read(path: Path): Pair<Graph, IrSummary> =
        Files.newInputStream(path).buffered().use { read(it) }

    fun read(input: InputStream): Pair<Graph, IrSummary> {
        var trailer: io.johnsonlee.graphite.ir.v1.Trailer? = null
        while (true) {
            val chunk = Chunk.parseDelimitedFrom(input) ?: break
            if (header == null && !chunk.hasHeader()) {
                throw IrFormatException("IR stream does not start with a header")
            }
            when (chunk.chunkCase) {
                Chunk.ChunkCase.HEADER -> readHeader(chunk.header)
                Chunk.ChunkCase.STRINGS -> strings.addAll(chunk.strings.valuesList)
                Chunk.ChunkCase.NODES -> chunk.nodes.nodesList.forEach { readNode(it) }
                Chunk.ChunkCase.EDGES -> chunk.edges.edgesList.forEach { readEdge(it) }
                Chunk.ChunkCase.METHODS -> chunk.methods.methodsList.forEach { builder.addMethod(method(it)) }
                Chunk.ChunkCase.TYPE_RELATIONS -> chunk.typeRelations.relationsList.forEach {
                    builder.addTypeRelation(type(it.subtype), type(it.supertype), typeRelation(it.relation))
                }
                Chunk.ChunkCase.CLASS_ORIGINS -> chunk.classOrigins.originsList.forEach {
                    builder.addClassOrigin(it.className, it.source)
                }
                Chunk.ChunkCase.ENUM_VALUES -> chunk.enumValues.entriesList.forEach {
                    builder.addEnumValues(it.enumClass, it.enumName, it.valuesList.map(::value))
                }
                Chunk.ChunkCase.ARTIFACT_DEPENDENCIES -> chunk.artifactDependencies.dependenciesList.forEach {
                    builder.addArtifactDependency(it.fromArtifact, it.toArtifact, it.weight)
                }
                Chunk.ChunkCase.TRAILER -> trailer = chunk.trailer
                Chunk.ChunkCase.CHUNK_NOT_SET -> throw IrFormatException("IR chunk carries nothing")
            }
            if (trailer != null) break
        }
        val head = header ?: throw IrFormatException("IR stream is empty")
        val tail = trailer ?: throw IrFormatException("IR stream is truncated: no trailer")
        check(tail, head)
        val dangling = referenced.filterNot { it in declared }.sorted()
        if (dangling.isNotEmpty()) {
            throw IrFormatException("IR refers to nodes it never declared: ${dangling.take(5)}")
        }
        return builder.build() to IrSummary(head, nodeCount, edgeCount, strings.size.toLong())
    }

    private fun readHeader(h: Header) {
        if (header != null) throw IrFormatException("IR stream has two headers")
        if (h.schemaVersion != IR_SCHEMA_VERSION) {
            throw IrFormatException("IR schema version ${h.schemaVersion} is not $IR_SCHEMA_VERSION")
        }
        header = h
    }

    private fun check(tail: io.johnsonlee.graphite.ir.v1.Trailer, head: Header) {
        if (tail.nodeCount != nodeCount) {
            throw IrFormatException("IR trailer declares ${tail.nodeCount} nodes, stream carried $nodeCount")
        }
        if (tail.edgeCount != edgeCount) {
            throw IrFormatException("IR trailer declares ${tail.edgeCount} edges, stream carried $edgeCount")
        }
        if (tail.stringCount != strings.size.toLong()) {
            throw IrFormatException("IR trailer declares ${tail.stringCount} strings, stream carried ${strings.size}")
        }
        if (head.language.isEmpty()) throw IrFormatException("IR header names no language")
    }

    private fun readNode(n: io.johnsonlee.graphite.ir.v1.Node) {
        if (!declared.add(n.id)) throw IrFormatException("IR declares node ${n.id} twice")
        nodeCount++
        val id = nodeId(n.id)
        val node: Node = when (n.kindCase) {
            io.johnsonlee.graphite.ir.v1.Node.KindCase.INT_CONSTANT -> IntConstant(id, n.intConstant.value)
            io.johnsonlee.graphite.ir.v1.Node.KindCase.LONG_CONSTANT -> LongConstant(id, n.longConstant.value)
            io.johnsonlee.graphite.ir.v1.Node.KindCase.FLOAT_CONSTANT -> FloatConstant(id, n.floatConstant.value)
            io.johnsonlee.graphite.ir.v1.Node.KindCase.DOUBLE_CONSTANT -> DoubleConstant(id, n.doubleConstant.value)
            io.johnsonlee.graphite.ir.v1.Node.KindCase.BOOLEAN_CONSTANT -> BooleanConstant(id, n.booleanConstant.value)
            io.johnsonlee.graphite.ir.v1.Node.KindCase.STRING_CONSTANT -> StringConstant(id, string(n.stringConstant.value))
            io.johnsonlee.graphite.ir.v1.Node.KindCase.NULL_CONSTANT -> NullConstant(id)
            io.johnsonlee.graphite.ir.v1.Node.KindCase.ENUM_CONSTANT -> EnumConstant(
                id,
                type(n.enumConstant.enumType),
                string(n.enumConstant.enumName),
                n.enumConstant.constructorArgsList.map(::value)
            )
            io.johnsonlee.graphite.ir.v1.Node.KindCase.LOCAL_VARIABLE -> LocalVariable(
                id,
                string(n.localVariable.name),
                type(n.localVariable.type),
                method(n.localVariable.method)
            )
            io.johnsonlee.graphite.ir.v1.Node.KindCase.FIELD -> FieldNode(
                id,
                FieldDescriptor(
                    type(n.field.field.declaringClass),
                    string(n.field.field.name),
                    type(n.field.field.type)
                ),
                n.field.isStatic
            )
            io.johnsonlee.graphite.ir.v1.Node.KindCase.PARAMETER -> ParameterNode(
                id,
                n.parameter.index,
                type(n.parameter.type),
                method(n.parameter.method)
            )
            io.johnsonlee.graphite.ir.v1.Node.KindCase.RETURN -> ReturnNode(
                id,
                method(n.`return`.method),
                if (n.`return`.hasActualType()) type(n.`return`.actualType) else null
            )
            io.johnsonlee.graphite.ir.v1.Node.KindCase.RESOURCE_FILE -> ResourceFileNode(
                id,
                n.resourceFile.path,
                n.resourceFile.source,
                n.resourceFile.format,
                if (n.resourceFile.hasProfile()) n.resourceFile.profile else null
            )
            io.johnsonlee.graphite.ir.v1.Node.KindCase.RESOURCE_VALUE -> ResourceValueNode(
                id,
                n.resourceValue.path,
                n.resourceValue.key,
                value(n.resourceValue.value),
                n.resourceValue.format,
                if (n.resourceValue.hasProfile()) n.resourceValue.profile else null
            )
            io.johnsonlee.graphite.ir.v1.Node.KindCase.CALL_SITE -> {
                val c = n.callSite
                CallSiteNode(
                    id,
                    method(c.caller),
                    method(c.callee),
                    if (c.hasLine()) c.line else null,
                    if (c.hasReceiver()) reference(c.receiver) else null,
                    c.argumentsList.map(::reference)
                )
            }
            io.johnsonlee.graphite.ir.v1.Node.KindCase.ANNOTATION -> {
                val a = n.annotation
                val values = a.valuesList.associate { it.name to value(it.value) }
                builder.addMemberAnnotation(a.className, a.memberName, a.name, values)
                AnnotationNode(id, a.name, a.className, a.memberName, values)
            }
            io.johnsonlee.graphite.ir.v1.Node.KindCase.KIND_NOT_SET ->
                throw IrFormatException("IR node ${n.id} has no kind")
        }
        builder.addNode(node)
    }

    private fun readEdge(e: io.johnsonlee.graphite.ir.v1.Edge) {
        edgeCount++
        val from = reference(e.from)
        val to = reference(e.to)
        val edge = when (e.kindCase) {
            io.johnsonlee.graphite.ir.v1.Edge.KindCase.DATA_FLOW ->
                DataFlowEdge(from, to, dataFlowKind(e.dataFlow.kind))
            io.johnsonlee.graphite.ir.v1.Edge.KindCase.RESOURCE ->
                ResourceEdge(from, to, resourceRelation(e.resource.kind))
            io.johnsonlee.graphite.ir.v1.Edge.KindCase.CALL ->
                CallEdge(from, to, e.call.isVirtual, e.call.isDynamic)
            io.johnsonlee.graphite.ir.v1.Edge.KindCase.TYPE ->
                TypeEdge(from, to, typeRelation(e.type.kind))
            io.johnsonlee.graphite.ir.v1.Edge.KindCase.CONTROL_FLOW -> ControlFlowEdge(
                from,
                to,
                controlFlowKind(e.controlFlow.kind),
                if (e.controlFlow.hasComparison()) {
                    BranchComparison(
                        comparisonOp(e.controlFlow.comparison.operator),
                        reference(e.controlFlow.comparison.comparand)
                    )
                } else {
                    null
                }
            )
            io.johnsonlee.graphite.ir.v1.Edge.KindCase.KIND_NOT_SET ->
                throw IrFormatException("IR edge ${e.from} -> ${e.to} has no kind")
        }
        builder.addEdge(edge)
    }

    private fun nodeId(irId: Int): NodeId = NodeId(irId + 1)

    private fun reference(irId: Int): NodeId {
        referenced.add(irId)
        return nodeId(irId)
    }

    private fun string(id: Int): String =
        strings.getOrNull(id) ?: throw IrFormatException("IR string $id is not in the table of ${strings.size}")

    private fun type(t: TypeRef): TypeDescriptor =
        TypeDescriptor(string(t.name), t.typeArgumentsList.map(::type))

    private fun method(m: MethodRef): MethodDescriptor = MethodDescriptor(
        type(m.declaringClass),
        string(m.name),
        m.parameterTypesList.map(::type),
        type(m.returnType)
    )

    private fun value(v: Value): Any? = when (v.valueCase) {
        Value.ValueCase.NULL_VALUE, Value.ValueCase.VALUE_NOT_SET -> null
        Value.ValueCase.STRING_VALUE -> v.stringValue
        Value.ValueCase.INT_VALUE -> v.intValue
        Value.ValueCase.DOUBLE_VALUE -> v.doubleValue
        Value.ValueCase.BOOL_VALUE -> v.boolValue
        Value.ValueCase.LIST_VALUE -> v.listValue.valuesList.map(::value)
        Value.ValueCase.MAP_VALUE -> v.mapValue.entriesList.associate { it.name to value(it.value) }
        Value.ValueCase.ENUM_VALUE -> EnumValueReference(v.enumValue.enumClass, v.enumValue.enumName)
    }

    private fun dataFlowKind(k: io.johnsonlee.graphite.ir.v1.DataFlowKind): DataFlowKind = when (k) {
        io.johnsonlee.graphite.ir.v1.DataFlowKind.ASSIGN -> DataFlowKind.ASSIGN
        io.johnsonlee.graphite.ir.v1.DataFlowKind.PARAMETER_PASS -> DataFlowKind.PARAMETER_PASS
        io.johnsonlee.graphite.ir.v1.DataFlowKind.RETURN_VALUE -> DataFlowKind.RETURN_VALUE
        io.johnsonlee.graphite.ir.v1.DataFlowKind.FIELD_STORE -> DataFlowKind.FIELD_STORE
        io.johnsonlee.graphite.ir.v1.DataFlowKind.FIELD_LOAD -> DataFlowKind.FIELD_LOAD
        io.johnsonlee.graphite.ir.v1.DataFlowKind.ARRAY_STORE -> DataFlowKind.ARRAY_STORE
        io.johnsonlee.graphite.ir.v1.DataFlowKind.ARRAY_LOAD -> DataFlowKind.ARRAY_LOAD
        io.johnsonlee.graphite.ir.v1.DataFlowKind.CAST -> DataFlowKind.CAST
        io.johnsonlee.graphite.ir.v1.DataFlowKind.PHI -> DataFlowKind.PHI
        io.johnsonlee.graphite.ir.v1.DataFlowKind.DATA_FLOW_KIND_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.DataFlowKind.UNRECOGNIZED -> throw IrFormatException("IR data-flow edge has no kind")
    }

    private fun resourceRelation(k: io.johnsonlee.graphite.ir.v1.ResourceRelation): ResourceRelation = when (k) {
        io.johnsonlee.graphite.ir.v1.ResourceRelation.OPENS -> ResourceRelation.OPENS
        io.johnsonlee.graphite.ir.v1.ResourceRelation.LOADS -> ResourceRelation.LOADS
        io.johnsonlee.graphite.ir.v1.ResourceRelation.BUNDLE_CANDIDATE -> ResourceRelation.BUNDLE_CANDIDATE
        io.johnsonlee.graphite.ir.v1.ResourceRelation.LOOKUP -> ResourceRelation.LOOKUP
        io.johnsonlee.graphite.ir.v1.ResourceRelation.ENUMERATES -> ResourceRelation.ENUMERATES
        io.johnsonlee.graphite.ir.v1.ResourceRelation.RESOURCE_RELATION_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.ResourceRelation.UNRECOGNIZED -> throw IrFormatException("IR resource edge has no kind")
    }

    private fun typeRelation(k: io.johnsonlee.graphite.ir.v1.TypeRelation): TypeRelation = when (k) {
        io.johnsonlee.graphite.ir.v1.TypeRelation.EXTENDS -> TypeRelation.EXTENDS
        io.johnsonlee.graphite.ir.v1.TypeRelation.IMPLEMENTS -> TypeRelation.IMPLEMENTS
        io.johnsonlee.graphite.ir.v1.TypeRelation.TYPE_RELATION_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.TypeRelation.UNRECOGNIZED -> throw IrFormatException("IR type relation has no kind")
    }

    private fun controlFlowKind(k: io.johnsonlee.graphite.ir.v1.ControlFlowKind): ControlFlowKind = when (k) {
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.SEQUENTIAL -> ControlFlowKind.SEQUENTIAL
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.BRANCH_TRUE -> ControlFlowKind.BRANCH_TRUE
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.BRANCH_FALSE -> ControlFlowKind.BRANCH_FALSE
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.SWITCH_CASE -> ControlFlowKind.SWITCH_CASE
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.SWITCH_DEFAULT -> ControlFlowKind.SWITCH_DEFAULT
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.EXCEPTION -> ControlFlowKind.EXCEPTION
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.RETURN -> ControlFlowKind.RETURN
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.CONTROL_FLOW_KIND_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.ControlFlowKind.UNRECOGNIZED -> throw IrFormatException("IR control-flow edge has no kind")
    }

    private fun comparisonOp(k: io.johnsonlee.graphite.ir.v1.ComparisonOp): ComparisonOp = when (k) {
        io.johnsonlee.graphite.ir.v1.ComparisonOp.EQ -> ComparisonOp.EQ
        io.johnsonlee.graphite.ir.v1.ComparisonOp.NE -> ComparisonOp.NE
        io.johnsonlee.graphite.ir.v1.ComparisonOp.LT -> ComparisonOp.LT
        io.johnsonlee.graphite.ir.v1.ComparisonOp.GE -> ComparisonOp.GE
        io.johnsonlee.graphite.ir.v1.ComparisonOp.GT -> ComparisonOp.GT
        io.johnsonlee.graphite.ir.v1.ComparisonOp.LE -> ComparisonOp.LE
        io.johnsonlee.graphite.ir.v1.ComparisonOp.COMPARISON_OP_UNSPECIFIED,
        io.johnsonlee.graphite.ir.v1.ComparisonOp.UNRECOGNIZED -> throw IrFormatException("IR branch comparison has no operator")
    }
}
