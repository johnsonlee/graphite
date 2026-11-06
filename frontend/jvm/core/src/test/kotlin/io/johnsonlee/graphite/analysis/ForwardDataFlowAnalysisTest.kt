package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ForwardDataFlowAnalysisTest : DataFlowAnalysisTestSupport() {
    @Test
    fun `forwardSlice finds return node`() {
        val method = makeMethod()
        val paramId = NodeId.next()
        val returnId = NodeId.next()
        val param = ParameterNode(paramId, 0, TypeDescriptor("int"), method)
        val returnNode = ReturnNode(returnId, method)
        val edge = DataFlowEdge(paramId, returnId, DataFlowKind.RETURN_VALUE)

        val graph = DefaultGraph.Builder().addNode(param).addNode(returnNode).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.forwardSlice(paramId)

        assertTrue(result.sources.any { it is SourceInfo.Return })
    }

    @Test
    fun `forwardSlice finds field sink`() {
        val method = makeMethod()
        val varId = NodeId.next()
        val fieldId = NodeId.next()
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), method)
        val fd = FieldDescriptor(TypeDescriptor("com.example.Foo"), "value", TypeDescriptor("int"))
        val field = FieldNode(fieldId, fd, isStatic = false)
        val edge = DataFlowEdge(varId, fieldId, DataFlowKind.FIELD_STORE)

        val graph = DefaultGraph.Builder().addNode(variable).addNode(field).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.forwardSlice(varId)

        assertEquals(1, result.fields().size)
    }

    @Test
    fun `forwardSlice respects maxDepth`() {
        val method = makeMethod()
        val paramId = NodeId.next()
        val param = ParameterNode(paramId, 0, TypeDescriptor("int"), method)
        val builder = DefaultGraph.Builder().addNode(param)

        var prevId = paramId
        for (i in 1..5) {
            val newId = NodeId.next()
            builder.addNode(LocalVariable(newId, "v$i", TypeDescriptor("int"), method))
            builder.addEdge(DataFlowEdge(prevId, newId, DataFlowKind.ASSIGN))
            prevId = newId
        }
        val returnId = NodeId.next()
        builder.addNode(ReturnNode(returnId, method))
        builder.addEdge(DataFlowEdge(prevId, returnId, DataFlowKind.RETURN_VALUE))

        val graph = builder.build()
        val analysis = DataFlowAnalysis(graph, AnalysisConfig(maxDepth = 2))
        val result = analysis.forwardSlice(paramId)
        // Should stop before reaching the return node at depth 6
        assertTrue(result.sources.filterIsInstance<SourceInfo.Return>().isEmpty())
    }

    @Test
    fun `forwardSlice handles null node gracefully`() {
        val graph = DefaultGraph.Builder().build()
        val analysis = DataFlowAnalysis(graph)
        val result = analysis.forwardSlice(NodeId(999))
        assertTrue(result.sources.isEmpty())
    }
}
