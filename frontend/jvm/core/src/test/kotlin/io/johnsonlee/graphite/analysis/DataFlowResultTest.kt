package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DataFlowResultTest : DataFlowAnalysisTestSupport() {
    @Test
    fun `constants returns only constant sources`() {
        val c1 = IntConstant(NodeId.next(), 1)
        val c2 = StringConstant(NodeId.next(), "hello")
        val sources = listOf(SourceInfo.Constant(c1, emptyList()), SourceInfo.Constant(c2, emptyList()))
        val result = DataFlowResult(sources, emptyList())
        assertEquals(2, result.constants().size)
    }

    @Test
    fun `intConstants returns int values`() {
        val c1 = IntConstant(NodeId.next(), 42)
        val c2 = StringConstant(NodeId.next(), "hello")
        val sources = listOf(SourceInfo.Constant(c1, emptyList()), SourceInfo.Constant(c2, emptyList()))
        val result = DataFlowResult(sources, emptyList())
        assertEquals(listOf(42), result.intConstants())
    }

    @Test
    fun `enumConstants returns enum values`() {
        val e = EnumConstant(NodeId.next(), TypeDescriptor("Status"), "ACTIVE", listOf(1))
        val sources = listOf(SourceInfo.Constant(e, emptyList()))
        val result = DataFlowResult(sources, emptyList())
        assertEquals(1, result.enumConstants().size)
    }

    @Test
    fun `allConstants includes enum fields`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.Status"), "ACTIVE", TypeDescriptor("com.example.Status"))
        val field = FieldNode(NodeId.next(), fd, isStatic = true)
        val sources = listOf(SourceInfo.Field(field, emptyList()))

        // Build graph with enum values
        val graph = DefaultGraph.Builder().addEnumValues("com.example.Status", "ACTIVE", listOf(1)).build()
        val result = DataFlowResult(sources, emptyList(), graph = graph)

        val allConstants = result.allConstants()
        assertEquals(1, allConstants.size)
        assertTrue(allConstants[0] is EnumConstant)
        assertEquals("ACTIVE", (allConstants[0] as EnumConstant).enumName)
    }

    @Test
    fun `fields returns field sources`() {
        val fd = FieldDescriptor(TypeDescriptor("Foo"), "f", TypeDescriptor("int"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)
        val sources = listOf(SourceInfo.Field(field, emptyList()))
        val result = DataFlowResult(sources, emptyList())
        assertEquals(1, result.fields().size)
    }

    @Test
    fun `propagationPathsBySourceType groups correctly`() {
        val paths =
            listOf(
                PropagationPath(emptyList(), PropagationSourceType.CONSTANT, 0),
                PropagationPath(emptyList(), PropagationSourceType.CONSTANT, 1),
                PropagationPath(emptyList(), PropagationSourceType.PARAMETER, 2))
        val result = DataFlowResult(emptyList(), emptyList(), paths)
        val grouped = result.propagationPathsBySourceType()
        assertEquals(2, grouped[PropagationSourceType.CONSTANT]?.size)
        assertEquals(1, grouped[PropagationSourceType.PARAMETER]?.size)
    }

    @Test
    fun `maxPropagationDepth returns max depth`() {
        val paths =
            listOf(
                PropagationPath(emptyList(), PropagationSourceType.CONSTANT, 2),
                PropagationPath(emptyList(), PropagationSourceType.CONSTANT, 5),
                PropagationPath(emptyList(), PropagationSourceType.CONSTANT, 3))
        val result = DataFlowResult(emptyList(), emptyList(), paths)
        assertEquals(5, result.maxPropagationDepth())
    }

    @Test
    fun `maxPropagationDepth returns 0 for empty paths`() {
        val result = DataFlowResult(emptyList(), emptyList())
        assertEquals(0, result.maxPropagationDepth())
    }

    @Test
    fun `constantsWithPaths pairs constants with paths`() {
        val c = IntConstant(NodeId.next(), 42)
        val path = PropagationPath(emptyList(), PropagationSourceType.CONSTANT, 1)
        val sources = listOf(SourceInfo.Constant(c, emptyList()))
        val result = DataFlowResult(sources, emptyList(), listOf(path))

        val pairs = result.constantsWithPaths()
        assertEquals(1, pairs.size)
        assertEquals(42, (pairs[0].first as IntConstant).value)
        assertEquals(path, pairs[0].second)
    }

    @Test
    fun `PropagationStep toDisplayString with location`() {
        val step = PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const int: 42", "Foo.bar():10", DataFlowKind.ASSIGN, 0)
        assertEquals("const int: 42 @ Foo.bar():10", step.toDisplayString())
    }

    @Test
    fun `PropagationStep toDisplayString without location`() {
        val step = PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const int: 42", null, null, 0)
        assertEquals("const int: 42", step.toDisplayString())
    }

    @Test
    fun `toDisplayString for empty path`() {
        val path = PropagationPath(emptyList(), PropagationSourceType.CONSTANT, 0)
        assertEquals("(empty path)", path.toDisplayString())
    }

    @Test
    fun `toDisplayString with steps`() {
        val steps =
            listOf(
                PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const 42", null, null, 0),
                PropagationStep(NodeId(2), PropagationNodeType.LOCAL_VARIABLE, "var x", "Foo.m()", DataFlowKind.ASSIGN, 1))
        val path = PropagationPath(steps, PropagationSourceType.CONSTANT, 1)
        assertTrue(path.toDisplayString().contains("→"))
    }

    @Test
    fun `toTreeString for empty path`() {
        val path = PropagationPath(emptyList(), PropagationSourceType.CONSTANT, 0)
        assertTrue(path.toTreeString().contains("(empty path)"))
    }

    @Test
    fun `toTreeString with steps`() {
        val steps =
            listOf(
                PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const 42", null, null, 0),
                PropagationStep(NodeId(2), PropagationNodeType.LOCAL_VARIABLE, "var x", "Foo.m()", DataFlowKind.ASSIGN, 1))
        val path = PropagationPath(steps, PropagationSourceType.CONSTANT, 1)
        val tree = path.toTreeString()
        assertTrue(tree.contains("└─"))
        assertTrue(tree.contains("[ASSIGN]"))
    }

    @Test
    fun `AnalysisConfig defaults`() {
        val config = AnalysisConfig()
        assertEquals(50, config.maxDepth)
        assertTrue(config.interProcedural)
        assertFalse(config.contextSensitive)
        assertTrue(config.flowSensitive)
    }

    @Test
    fun `DataFlowPath length`() {
        val path = DataFlowPath(listOf(NodeId(1), NodeId(2), NodeId(3)))
        assertEquals(3, path.length)
    }

    @Test
    fun `PropagationNodeType has all expected values`() {
        assertEquals(8, PropagationNodeType.entries.size)
    }

    @Test
    fun `PropagationSourceType has all expected values`() {
        assertEquals(5, PropagationSourceType.entries.size)
    }

    @Test
    fun `constantsWithPaths includes enum field constants`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.Status"), "ACTIVE", TypeDescriptor("com.example.Status"))
        val field = FieldNode(NodeId.next(), fd, isStatic = true)
        val constSource = SourceInfo.Constant(IntConstant(NodeId.next(), 42), emptyList())
        val fieldSource = SourceInfo.Field(field, emptyList())

        val path1 = PropagationPath(emptyList(), PropagationSourceType.CONSTANT, 0)
        val path2 = PropagationPath(emptyList(), PropagationSourceType.ENUM_CONSTANT, 1)

        val builder = DefaultGraph.Builder()
        builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1))
        val graph = builder.build()

        val result = DataFlowResult(listOf(constSource, fieldSource), emptyList(), listOf(path1, path2), graph)

        val pairs = result.constantsWithPaths()
        assertEquals(2, pairs.size)
        assertTrue(pairs[0].first is IntConstant)
        assertTrue(pairs[1].first is EnumConstant)
    }

    @Test
    fun `allConstants without graph returns only direct constants`() {
        val c = IntConstant(NodeId.next(), 42)
        val fd = FieldDescriptor(TypeDescriptor("com.example.Status"), "ACTIVE", TypeDescriptor("com.example.Status"))
        val field = FieldNode(NodeId.next(), fd, isStatic = true)
        val sources = listOf(SourceInfo.Constant(c, emptyList()), SourceInfo.Field(field, emptyList()))
        val result = DataFlowResult(sources, emptyList())
        val allConsts = result.allConstants()
        // Without graph, enum values returns emptyList
        assertEquals(2, allConsts.size)
    }

    @Test
    fun `allConstants skips non-enum fields`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.Foo"), "value", TypeDescriptor("int"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)
        val sources = listOf(SourceInfo.Field(field, emptyList()))
        val result = DataFlowResult(sources, emptyList())
        val allConsts = result.allConstants()
        assertTrue(allConsts.isEmpty()) // Not an enum field
    }

    @Test
    fun `createPropagationStep handles unknown node type`() {
        // EnumConstant is not explicitly handled in the when block for some branches
        // Create a node type that falls into the else branch
        val builder = DefaultGraph.Builder()
        val method = MethodDescriptor(TypeDescriptor("com.example.Foo"), "test", emptyList(), TypeDescriptor("void"))
        builder.addMethod(method)

        // FieldNode (non-static) should hit the else branch in createPropagationStep
        val fieldDesc = FieldDescriptor(TypeDescriptor("com.example.Foo"), "data", TypeDescriptor("java.lang.Object"))
        val fieldId = NodeId.next()
        val fieldNode = FieldNode(fieldId, fieldDesc, isStatic = false)
        builder.addNode(fieldNode)

        // Chain: IntConstant -> FieldNode (non-static) -> target
        val constId = NodeId.next()
        val constNode = IntConstant(constId, 42)
        builder.addNode(constNode)
        builder.addEdge(DataFlowEdge(constId, fieldId, DataFlowKind.FIELD_STORE))

        val targetId = NodeId.next()
        val target = LocalVariable(targetId, "x", TypeDescriptor("int"), method)
        builder.addNode(target)
        builder.addEdge(DataFlowEdge(fieldId, targetId, DataFlowKind.FIELD_LOAD))

        val graph = builder.build()
        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(targetId)

        // The backward slice traverses through the FieldNode (non-static)
        // which should hit the else branch in createPropagationStep
        assertTrue(result.propagationPaths.isNotEmpty())
    }

    @Test
    fun `traceParameterSources traces caller arguments into callee parameter`() {
        val builder = DefaultGraph.Builder()
        val callerMethod = MethodDescriptor(TypeDescriptor("com.example.Caller"), "call", emptyList(), TypeDescriptor("void"))
        val calleeMethod =
            MethodDescriptor(TypeDescriptor("com.example.Callee"), "process", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
        builder.addMethod(callerMethod)
        builder.addMethod(calleeMethod)

        // In the callee, a parameter node
        val paramId = NodeId.next()
        val param = ParameterNode(paramId, 0, TypeDescriptor("int"), calleeMethod)
        builder.addNode(param)

        // In the caller, a constant passed as argument to the callee
        val constId = NodeId.next()
        val constant = IntConstant(constId, 100)
        builder.addNode(constant)

        // Call site from caller to callee
        val callSiteId = NodeId.next()
        val callSite = CallSiteNode(callSiteId, callerMethod, calleeMethod, 15, NodeId.next(), listOf(constId))
        builder.addNode(callSite)

        val graph = builder.build()
        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(paramId)

        assertTrue(result.sources.any { it is SourceInfo.Parameter && it.node.id == paramId })
        assertEquals(listOf(100), result.intConstants())
    }

    @Test
    fun `traceParameterSources matches the full method signature`() {
        val builder = DefaultGraph.Builder()
        val callerMethod = MethodDescriptor(TypeDescriptor("com.example.Caller"), "call", emptyList(), TypeDescriptor("void"))
        val intOverload =
            MethodDescriptor(TypeDescriptor("com.example.Callee"), "process", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
        val stringOverload =
            MethodDescriptor(
                TypeDescriptor("com.example.Callee"), "process", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("void"))
        builder.addMethod(callerMethod)
        builder.addMethod(intOverload)
        builder.addMethod(stringOverload)

        val paramId = NodeId.next()
        builder.addNode(ParameterNode(paramId, 0, TypeDescriptor("int"), intOverload))

        val intConstId = NodeId.next()
        builder.addNode(IntConstant(intConstId, 100))
        val stringConstId = NodeId.next()
        builder.addNode(StringConstant(stringConstId, "wrong-overload"))

        builder.addNode(CallSiteNode(NodeId.next(), callerMethod, intOverload, 10, null, listOf(intConstId)))
        builder.addNode(CallSiteNode(NodeId.next(), callerMethod, stringOverload, 11, null, listOf(stringConstId)))

        val graph = builder.build()
        val result = DataFlowAnalysis(graph).backwardSlice(paramId)

        assertEquals(listOf(100), result.intConstants())
        assertTrue(result.constants().none { it is StringConstant })
    }
}
