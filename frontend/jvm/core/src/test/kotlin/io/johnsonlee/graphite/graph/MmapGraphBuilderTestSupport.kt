package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.BeforeTest

internal abstract class MmapGraphBuilderTestSupport {
    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    protected fun makeMethod(className: String, name: String, params: List<String> = emptyList()): MethodDescriptor {
        return MethodDescriptor(TypeDescriptor(className), name, params.map { TypeDescriptor(it) }, TypeDescriptor("void"))
    }

    /**
     * Builds a small but realistic graph for testing MmapGraph operations. Contains a mix of node types, edge types, methods, type
     * hierarchy, enum values, member annotations, and branch scopes.
     */
    protected fun buildTestGraph(): Triple<Graph, Map<String, NodeId>, Map<String, MethodDescriptor>> {
        val ids = mutableMapOf<String, NodeId>()
        fun id(name: String): NodeId = ids.getOrPut(name) { NodeId.next() }

        val mainRun = makeMethod("com.example.Main", "run")
        val serviceProcess = makeMethod("com.example.Service", "process", listOf("int", "java.lang.String"))
        val serviceHelper = makeMethod("com.example.Service", "helper")
        val methods = mapOf("mainRun" to mainRun, "serviceProcess" to serviceProcess, "serviceHelper" to serviceHelper)

        val builder = MmapGraphBuilder()

        // Nodes: constants
        builder.addNode(IntConstant(id("int1"), 42))
        builder.addNode(IntConstant(id("int2"), 99))
        builder.addNode(StringConstant(id("str1"), "hello"))
        builder.addNode(LongConstant(id("long1"), 1000L))
        builder.addNode(BooleanConstant(id("bool1"), true))
        builder.addNode(NullConstant(id("null1")))

        // Nodes: structural
        builder.addNode(ParameterNode(id("param1"), 0, TypeDescriptor("int"), serviceProcess))
        builder.addNode(ParameterNode(id("param2"), 1, TypeDescriptor("java.lang.String"), serviceProcess))
        builder.addNode(ReturnNode(id("ret1"), serviceProcess, TypeDescriptor("java.lang.String")))
        builder.addNode(LocalVariable(id("local1"), "x", TypeDescriptor("int"), mainRun))
        builder.addNode(
            FieldNode(id("field1"), FieldDescriptor(TypeDescriptor("com.example.Service"), "count", TypeDescriptor("int")), false))

        // Nodes: call sites
        builder.addNode(CallSiteNode(id("call1"), mainRun, serviceProcess, 10, null, listOf(id("int1"), id("str1"))))
        builder.addNode(CallSiteNode(id("call2"), mainRun, serviceHelper, 20, null, emptyList()))

        // Edges: data flow
        builder.addEdge(DataFlowEdge(id("int1"), id("call1"), DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(id("str1"), id("call1"), DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(id("param1"), id("local1"), DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(id("local1"), id("ret1"), DataFlowKind.RETURN_VALUE))

        // Edges: call
        builder.addEdge(CallEdge(id("call1"), id("param1"), isVirtual = true))
        builder.addEdge(CallEdge(id("call2"), id("ret1"), isVirtual = false, isDynamic = true))

        // Edges: type
        builder.addEdge(TypeEdge(id("field1"), id("int1"), TypeRelation.EXTENDS))

        // Edges: control flow with comparison
        builder.addEdge(
            ControlFlowEdge(id("bool1"), id("call1"), ControlFlowKind.BRANCH_TRUE, BranchComparison(ComparisonOp.EQ, id("int2"))))
        builder.addEdge(ControlFlowEdge(id("bool1"), id("call2"), ControlFlowKind.BRANCH_FALSE))

        // Methods
        builder.addMethod(mainRun)
        builder.addMethod(serviceProcess)
        builder.addMethod(serviceHelper)

        // Type hierarchy
        builder.addTypeRelation(TypeDescriptor("com.example.ServiceImpl"), TypeDescriptor("com.example.Service"), TypeRelation.EXTENDS)
        builder.addTypeRelation(TypeDescriptor("com.example.ServiceImpl"), TypeDescriptor("com.example.Runnable"), TypeRelation.IMPLEMENTS)
        builder.addTypeRelation(TypeDescriptor("com.example.AdvancedService"), TypeDescriptor("com.example.Service"), TypeRelation.EXTENDS)

        // Enum values
        builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active", true))
        builder.addEnumValues("com.example.Status", "INACTIVE", listOf(0, "inactive", false))

        // Member annotations
        builder.addMemberAnnotation("com.example.User", "name", "javax.validation.NotNull", mapOf("message" to "required"))
        builder.addMemberAnnotation("com.example.User", "name", "javax.persistence.Column", mapOf("length" to 255))
        builder.addMemberAnnotation("com.example.User", "id", "javax.persistence.Id")

        // Branch scopes
        builder.addBranchScope(
            id("bool1"),
            mainRun,
            BranchComparison(ComparisonOp.EQ, id("int2")),
            intArrayOf(id("call1").value),
            intArrayOf(id("call2").value))

        return Triple(builder.build(), ids, methods)
    }

    protected fun bytes(writer: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { output ->
                output.writer()
                output.flush()
            }
            baos.toByteArray()
        }

    protected fun dataInput(writer: DataOutputStream.() -> Unit): DataInputStream = DataInputStream(ByteArrayInputStream(bytes(writer)))

    protected fun edgeInput(from: Int, to: Int, writePayload: DataOutputStream.() -> Unit): DataInputStream = dataInput {
        writeInt(from)
        writeInt(to)
        writePayload()
    }
}
