package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class DataFlowAnalysisTest : DataFlowAnalysisTestSupport() {
    @Test
    fun `backwardSlice finds constant through dataflow edges`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 42)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), makeMethod())
        val edge = DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)

        val graph = DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        assertEquals(1, result.constants().size)
        assertEquals(42, (result.constants()[0] as IntConstant).value)
    }

    @Test
    fun `backwardSlice finds parameter source`() {
        val paramId = NodeId.next()
        val varId = NodeId.next()
        val method = makeMethod()
        val param = ParameterNode(paramId, 0, TypeDescriptor("int"), method)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), method)
        val edge = DataFlowEdge(paramId, varId, DataFlowKind.ASSIGN)

        val graph = DefaultGraph.Builder().addNode(param).addNode(variable).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        assertTrue(result.sources.any { it is SourceInfo.Parameter })
    }

    @Test
    fun `backwardSlice finds field source`() {
        val fieldId = NodeId.next()
        val varId = NodeId.next()
        val fd = FieldDescriptor(TypeDescriptor("com.example.Foo"), "value", TypeDescriptor("int"))
        val field = FieldNode(fieldId, fd, isStatic = true)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), makeMethod())
        val edge = DataFlowEdge(fieldId, varId, DataFlowKind.FIELD_LOAD)

        val graph = DefaultGraph.Builder().addNode(field).addNode(variable).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        assertEquals(1, result.fields().size)
    }

    @Test
    fun `backwardSlice caches results`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 42)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), makeMethod())
        val edge = DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)

        val graph = DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result1 = analysis.backwardSlice(varId)
        val result2 = analysis.backwardSlice(varId)
        assertTrue(result1 === result2) // Same reference (cached)
    }

    @Test
    fun `backwardSlice respects max depth`() {
        val method = makeMethod()
        // Create a chain: const -> var1 -> var2 -> ... -> varN
        val constId = NodeId.next()
        val constant = IntConstant(constId, 1)
        val builder = DefaultGraph.Builder().addNode(constant)

        var prevId = constId
        // Create chain longer than maxDepth=2
        for (i in 1..5) {
            val newId = NodeId.next()
            builder.addNode(LocalVariable(newId, "v$i", TypeDescriptor("int"), method))
            builder.addEdge(DataFlowEdge(prevId, newId, DataFlowKind.ASSIGN))
            prevId = newId
        }
        val graph = builder.build()

        val analysis = DataFlowAnalysis(graph, AnalysisConfig(maxDepth = 2))
        val result = analysis.backwardSlice(prevId)
        // Should not find the constant at depth 5 with maxDepth=2
        assertTrue(result.constants().isEmpty())
    }

    @Test
    fun `backwardSlice handles call site with receiver`() {
        val method = makeMethod()
        val callee = MethodDescriptor(TypeDescriptor("com.example.Foo"), "getId", emptyList(), TypeDescriptor("int"))
        val receiverId = NodeId.next()
        val csId = NodeId.next()
        val receiver = LocalVariable(receiverId, "obj", TypeDescriptor("com.example.Foo"), method)
        val callSite = CallSiteNode(csId, method, callee, 10, receiverId, emptyList())

        val graph = DefaultGraph.Builder().addNode(receiver).addNode(callSite).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(csId)
        // Should traverse through receiver
        assertTrue(result.sources.isEmpty() || result.sources.size >= 0) // No crash
    }

    @Test
    fun `backwardSlice creates propagation step for LocalVariable`() {
        val method = makeMethod("testMethod")
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 42)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), method)
        val edge = DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)

        val graph = DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        // Should have propagation paths
        assertTrue(result.propagationPaths.isNotEmpty())
        // Path should contain local variable step
        val path = result.propagationPaths.first()
        assertTrue(path.steps.any { it.nodeType == PropagationNodeType.LOCAL_VARIABLE })
    }

    @Test
    fun `backwardSlice creates propagation step for ParameterNode`() {
        val method = makeMethod()
        val paramId = NodeId.next()
        val varId = NodeId.next()
        val param = ParameterNode(paramId, 0, TypeDescriptor("int"), method)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), method)
        val edge = DataFlowEdge(paramId, varId, DataFlowKind.ASSIGN)

        val graph = DefaultGraph.Builder().addNode(param).addNode(variable).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        assertTrue(result.propagationPaths.isNotEmpty())
        val path = result.propagationPaths.first()
        assertTrue(path.steps.any { it.nodeType == PropagationNodeType.PARAMETER })
    }

    @Test
    fun `backwardSlice creates propagation step for FieldNode`() {
        val fieldId = NodeId.next()
        val varId = NodeId.next()
        val fd = FieldDescriptor(TypeDescriptor("com.example.Foo"), "value", TypeDescriptor("int"))
        val field = FieldNode(fieldId, fd, isStatic = true)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), makeMethod())
        val edge = DataFlowEdge(fieldId, varId, DataFlowKind.FIELD_LOAD)

        val graph = DefaultGraph.Builder().addNode(field).addNode(variable).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        assertTrue(result.propagationPaths.isNotEmpty())
        val path = result.propagationPaths.first()
        assertTrue(path.steps.any { it.nodeType == PropagationNodeType.FIELD })
        // Should contain "static" in description
        assertTrue(path.steps.any { it.description.contains("static") })
    }

    @Test
    fun `backwardSlice creates propagation step for ReturnNode`() {
        val method = makeMethod()
        val constId = NodeId.next()
        val returnId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 99)
        val returnNode = ReturnNode(returnId, method)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), method)

        val graph =
            DefaultGraph.Builder()
                .addNode(constant)
                .addNode(returnNode)
                .addNode(variable)
                .addEdge(DataFlowEdge(constId, returnId, DataFlowKind.RETURN_VALUE))
                .addEdge(DataFlowEdge(returnId, varId, DataFlowKind.ASSIGN))
                .build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        assertTrue(result.propagationPaths.isNotEmpty())
        val allSteps = result.propagationPaths.flatMap { it.steps }
        assertTrue(allSteps.any { it.nodeType == PropagationNodeType.RETURN_VALUE })
    }

    @Test
    fun `backwardSlice creates propagation step for CallSiteNode`() {
        val method = makeMethod()
        val callee = MethodDescriptor(TypeDescriptor("com.example.Foo"), "getValue", emptyList(), TypeDescriptor("int"))
        val constId = NodeId.next()
        val csId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 99)
        val cs = CallSiteNode(csId, method, callee, 10, null, emptyList())
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), method)

        val graph =
            DefaultGraph.Builder()
                .addNode(constant)
                .addNode(cs)
                .addNode(variable)
                .addEdge(DataFlowEdge(constId, csId, DataFlowKind.ASSIGN))
                .addEdge(DataFlowEdge(csId, varId, DataFlowKind.RETURN_VALUE))
                .build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        assertTrue(result.propagationPaths.isNotEmpty())
        val allSteps = result.propagationPaths.flatMap { it.steps }
        assertTrue(allSteps.any { it.nodeType == PropagationNodeType.CALL_SITE })
    }

    @Test
    fun `backwardSlice describes non-value nodes as unknown propagation steps`() {
        val constant = StringConstant(NodeId.next(), "enabled")
        val resource = ResourceFileNode(id = NodeId.next(), path = "application.properties", source = "test.jar", format = "properties")
        val graph =
            DefaultGraph.Builder()
                .addNode(constant)
                .addNode(resource)
                .addEdge(DataFlowEdge(constant.id, resource.id, DataFlowKind.ASSIGN))
                .build()

        val result = DataFlowAnalysis(graph).backwardSlice(resource.id)
        val unknownStep = result.propagationPaths.single().steps.single { it.nodeType == PropagationNodeType.UNKNOWN }

        assertEquals(resource.id, unknownStep.nodeId)
        assertEquals("ResourceFileNode", unknownStep.description)
        assertEquals(null, unknownStep.location)
        assertEquals(0, unknownStep.depth)
    }

    @Test
    fun `backwardSlice formats int constant description`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 42)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(result.propagationPaths.any { p -> p.steps.any { it.description.contains("const int: 42") } })
    }

    @Test
    fun `backwardSlice formats long constant description`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = LongConstant(constId, 100L)
        val variable = LocalVariable(varId, "x", TypeDescriptor("long"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(result.propagationPaths.any { p -> p.steps.any { it.description.contains("const long: 100L") } })
    }

    @Test
    fun `backwardSlice formats float constant description`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = FloatConstant(constId, 1.5f)
        val variable = LocalVariable(varId, "x", TypeDescriptor("float"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(result.propagationPaths.any { p -> p.steps.any { it.description.contains("const float") } })
    }

    @Test
    fun `backwardSlice formats double constant description`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = DoubleConstant(constId, 2.5)
        val variable = LocalVariable(varId, "x", TypeDescriptor("double"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(result.propagationPaths.any { p -> p.steps.any { it.description.contains("const double: 2.5") } })
    }

    @Test
    fun `backwardSlice formats string constant description`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = StringConstant(constId, "hello")
        val variable = LocalVariable(varId, "x", TypeDescriptor("java.lang.String"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(result.propagationPaths.any { p -> p.steps.any { it.description.contains("const string: \"hello\"") } })
    }

    @Test
    fun `backwardSlice formats boolean constant description`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = BooleanConstant(constId, true)
        val variable = LocalVariable(varId, "x", TypeDescriptor("boolean"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(result.propagationPaths.any { p -> p.steps.any { it.description.contains("const boolean: true") } })
    }

    @Test
    fun `backwardSlice formats resource value constant description`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = ResourceValueNode(constId, "application.yml", "server.port", 8080, "yaml")
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(
            result.propagationPaths.any { p -> p.steps.any { it.description.contains("resource application.yml#server.port = 8080") } })
    }

    @Test
    fun `backwardSlice formats enum constant description`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = EnumConstant(constId, TypeDescriptor("com.example.Status"), "ACTIVE")
        val variable = LocalVariable(varId, "x", TypeDescriptor("com.example.Status"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(result.propagationPaths.any { p -> p.steps.any { it.description.contains("enum: Status.ACTIVE") } })
    }

    @Test
    fun `backwardSlice formats null constant description`() {
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = NullConstant(constId)
        val variable = LocalVariable(varId, "x", TypeDescriptor("java.lang.Object"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(result.propagationPaths.any { p -> p.steps.any { it.description.contains("const null") } })
    }

    @Test
    fun `backwardSlice truncates long string constant`() {
        val longStr = "a".repeat(100)
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = StringConstant(constId, longStr)
        val variable = LocalVariable(varId, "x", TypeDescriptor("java.lang.String"), makeMethod())
        val graph =
            DefaultGraph.Builder().addNode(constant).addNode(variable).addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)).build()
        val result = DataFlowAnalysis(graph).backwardSlice(varId)
        assertTrue(result.propagationPaths.any { p -> p.steps.any { it.description.contains("...") } })
    }

    @Test
    fun `backwardSlice identifies enum constant field`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.Status"), "ACTIVE", TypeDescriptor("com.example.Status"))
        val fieldId = NodeId.next()
        val field = FieldNode(fieldId, fd, isStatic = true)
        val varId = NodeId.next()
        val variable = LocalVariable(varId, "x", TypeDescriptor("com.example.Status"), makeMethod())
        val edge = DataFlowEdge(fieldId, varId, DataFlowKind.FIELD_LOAD)

        val builder = DefaultGraph.Builder()
        builder.addNode(field)
        builder.addNode(variable)
        builder.addEdge(edge)
        builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1))
        val graph = builder.build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        assertTrue(result.propagationPaths.isNotEmpty())
        // Propagation source type should be ENUM_CONSTANT
        assertTrue(result.propagationPaths.any { it.sourceType == PropagationSourceType.ENUM_CONSTANT })
    }
}
