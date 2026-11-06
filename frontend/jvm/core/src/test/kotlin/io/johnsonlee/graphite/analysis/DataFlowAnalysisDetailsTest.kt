package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DataFlowAnalysisDetailsTest : DataFlowAnalysisTestSupport() {
    @Test
    fun `backwardSlice handles null node gracefully`() {
        // Empty graph - node lookup returns null
        val graph = DefaultGraph.Builder().build()
        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(NodeId(999))
        assertTrue(result.constants().isEmpty())
    }

    @Test
    fun `backwardSlice creates propagation step for non-static FieldNode`() {
        val fieldId = NodeId.next()
        val varId = NodeId.next()
        val fd = FieldDescriptor(TypeDescriptor("com.example.Foo"), "value", TypeDescriptor("int"))
        val field = FieldNode(fieldId, fd, isStatic = false)
        val variable = LocalVariable(varId, "x", TypeDescriptor("int"), makeMethod())
        val edge = DataFlowEdge(fieldId, varId, DataFlowKind.FIELD_LOAD)

        val graph = DefaultGraph.Builder().addNode(field).addNode(variable).addEdge(edge).build()

        val analysis = DataFlowAnalysis(graph)
        val result = analysis.backwardSlice(varId)

        assertTrue(result.propagationPaths.isNotEmpty())
        val fieldStep = result.propagationPaths.flatMap { it.steps }.first { it.nodeType == PropagationNodeType.FIELD }
        assertFalse(fieldStep.description.contains("static"))
    }

    @Test
    fun `backwardSlice creates propagation step for ReturnNode with actualType`() {
        val method = makeMethod()
        val constId = NodeId.next()
        val returnId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 99)
        val returnNode = ReturnNode(returnId, method, TypeDescriptor("com.example.User"))
        val variable = LocalVariable(varId, "x", TypeDescriptor("java.lang.Object"), method)

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
        val returnStep = result.propagationPaths.flatMap { it.steps }.first { it.nodeType == PropagationNodeType.RETURN_VALUE }
        assertTrue(returnStep.description.contains("User"))
    }
}
