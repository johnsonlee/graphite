package io.johnsonlee.graphite.query

import io.johnsonlee.graphite.analysis.PropagationNodeType
import io.johnsonlee.graphite.analysis.PropagationPath
import io.johnsonlee.graphite.analysis.PropagationSourceType
import io.johnsonlee.graphite.analysis.PropagationStep
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class QueryDslTest : QueryDslTestSupport() {
    @Test
    fun `findArgumentConstants finds constant passed to matching call site`() {
        val callerMethod = makeMethod(name = "main")
        val calleeMethod =
            MethodDescriptor(
                TypeDescriptor("com.example.Client"), "getOption", listOf(TypeDescriptor("java.lang.Integer")), TypeDescriptor("boolean"))

        val constId = NodeId.next()
        val constant = IntConstant(constId, 1001)

        val csId = NodeId.next()
        val callSite = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(constId))

        val graph = DefaultGraph.Builder().addNode(constant).addNode(callSite).build()

        val query = GraphiteQuery(graph)
        val results =
            query.findArgumentConstants {
                method {
                    declaringClass = "com.example.Client"
                    name = "getOption"
                }
                argumentIndex = 0
            }

        assertEquals(1, results.size)
        assertEquals(1001, results[0].value)
        assertEquals(0, results[0].argumentIndex)
    }

    @Test
    fun `findArgumentConstants with multiple argument indices`() {
        val callerMethod = makeMethod(name = "main")
        val calleeMethod =
            MethodDescriptor(
                TypeDescriptor("com.example.Client"),
                "setConfig",
                listOf(TypeDescriptor("int"), TypeDescriptor("java.lang.String")),
                TypeDescriptor("void"))

        val arg0Id = NodeId.next()
        val arg1Id = NodeId.next()
        val arg0 = IntConstant(arg0Id, 42)
        val arg1 = StringConstant(arg1Id, "hello")

        val csId = NodeId.next()
        val callSite = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(arg0Id, arg1Id))

        val graph = DefaultGraph.Builder().addNode(arg0).addNode(arg1).addNode(callSite).build()

        val query = GraphiteQuery(graph)
        val results =
            query.findArgumentConstants {
                method {
                    declaringClass = "com.example.Client"
                    name = "setConfig"
                }
                argumentIndices = listOf(0, 1)
            }

        assertEquals(2, results.size)
        assertTrue(results.any { it.argumentIndex == 0 && it.value == 42 })
        assertTrue(results.any { it.argumentIndex == 1 && it.value == "hello" })
    }

    @Test
    fun `findArgumentConstants with dataflow through variable`() {
        val callerMethod = makeMethod(name = "main")
        val calleeMethod =
            MethodDescriptor(TypeDescriptor("com.example.Client"), "getOption", listOf(TypeDescriptor("int")), TypeDescriptor("boolean"))

        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 999)
        val variable = LocalVariable(varId, "optionId", TypeDescriptor("int"), callerMethod)

        val csId = NodeId.next()
        val callSite = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(varId))

        val edge = DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)

        val graph = DefaultGraph.Builder().addNode(constant).addNode(variable).addNode(callSite).addEdge(edge).build()

        val query = GraphiteQuery(graph)
        val results =
            query.findArgumentConstants {
                method {
                    declaringClass = "com.example.Client"
                    name = "getOption"
                }
            }

        assertEquals(1, results.size)
        assertEquals(999, results[0].value)
    }

    @Test
    fun `findArgumentConstants returns empty for no matching call sites`() {
        val graph = DefaultGraph.Builder().build()
        val query = GraphiteQuery(graph)
        val results =
            query.findArgumentConstants {
                method {
                    declaringClass = "com.example.Missing"
                    name = "missing"
                }
            }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `resolveIndices returns argumentIndices when set`() {
        val query =
            ArgumentConstantQuery().apply {
                argumentIndices = listOf(0, 1, 2)
                argumentIndex = 5
            }
        assertEquals(listOf(0, 1, 2), query.resolveIndices())
    }

    @Test
    fun `resolveIndices returns argumentIndex when no argumentIndices`() {
        val query = ArgumentConstantQuery().apply { argumentIndex = 3 }
        assertEquals(listOf(3), query.resolveIndices())
    }

    @Test
    fun `resolveIndices defaults to 0`() {
        val query = ArgumentConstantQuery()
        assertEquals(listOf(0), query.resolveIndices())
    }

    @Test
    fun `MethodPatternBuilder builds pattern`() {
        val builder =
            MethodPatternBuilder().apply {
                declaringClass = "com.example.Foo"
                name = "bar"
                parameterTypes = listOf("int")
                returnType = "void"
                useRegex = true
            }
        val pattern = builder.build()
        assertEquals("com.example.Foo", pattern.declaringClass)
        assertEquals("bar", pattern.name)
        assertEquals(listOf("int"), pattern.parameterTypes)
        assertTrue(pattern.useRegex)
    }

    @Test
    fun `ArgumentConstantResult location format`() {
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Client", "getOption")
        val cs = CallSiteNode(NodeId.next(), caller, callee, 42, null, emptyList())
        val constant = IntConstant(NodeId.next(), 1001)

        val result = ArgumentConstantResult(cs, 0, constant, null, null)
        assertTrue(result.location.contains("42"))
        assertEquals(1001, result.value)
    }

    @Test
    fun `ArgumentConstantResult propagationDepth with null path`() {
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Client", "getOption")
        val cs = CallSiteNode(NodeId.next(), caller, callee, 10, null, emptyList())
        val constant = IntConstant(NodeId.next(), 1)

        val result = ArgumentConstantResult(cs, 0, constant, null, null)
        assertEquals(0, result.propagationDepth)
        assertEquals("(direct)", result.propagationDescription)
        assertFalse(result.involvesReturnValue)
        assertFalse(result.involvesFieldAccess)
    }

    @Test
    fun `ArgumentConstantResult involvesReturnValue true when path has call site`() {
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Client", "getOption")
        val cs = CallSiteNode(NodeId.next(), caller, callee, 10, null, emptyList())
        val constant = IntConstant(NodeId.next(), 1)

        val step = PropagationStep(NodeId(1), PropagationNodeType.CALL_SITE, "call", null, null, 0)
        val path = PropagationPath(listOf(step), PropagationSourceType.RETURN_VALUE, 1)

        val result = ArgumentConstantResult(cs, 0, constant, null, path)
        assertTrue(result.involvesReturnValue)
    }

    @Test
    fun `ArgumentConstantResult involvesFieldAccess true when path has field`() {
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Client", "getOption")
        val cs = CallSiteNode(NodeId.next(), caller, callee, 10, null, emptyList())
        val constant = IntConstant(NodeId.next(), 1)

        val step = PropagationStep(NodeId(1), PropagationNodeType.FIELD, "field", null, null, 0)
        val path = PropagationPath(listOf(step), PropagationSourceType.FIELD, 1)

        val result = ArgumentConstantResult(cs, 0, constant, null, path)
        assertTrue(result.involvesFieldAccess)
    }

    @Test
    fun `ArgumentConstantQuery config DSL`() {
        val query = ArgumentConstantQuery()
        query.config { copy(maxDepth = 30) }
        assertEquals(30, query.analysisConfig.maxDepth)
    }
}
