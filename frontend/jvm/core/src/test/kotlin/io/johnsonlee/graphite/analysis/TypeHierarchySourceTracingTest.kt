package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeHierarchyConfig
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertTrue

internal class TypeHierarchySourceTracingTest : TypeHierarchyAnalysisTestSupport() {
    @Test
    fun `analysis traces through call site return types`() {
        val builder = DefaultGraph.Builder()

        val getterMethod = method("com.example.Repo", "findUser", userType)
        val callerMethod = method("com.example.Controller", "getUser", userType)
        builder.addMethod(getterMethod)
        builder.addMethod(callerMethod)

        // Call site: repo.findUser()
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, getterMethod, 10, null, emptyList())
        builder.addNode(cs)

        // Return node in caller
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, callerMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(csId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(callerMethod)

        assertTrue(result.returnStructures.isNotEmpty())
        assertTrue(result.returnStructures.any { it.className == "com.example.User" })
    }

    @Test
    fun `analysis traces through Object-typed locals`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", objectType)
        builder.addMethod(testMethod)

        // Object local -> actual User local
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)

        val objVarId = NodeId.next()
        val objVar = LocalVariable(objVarId, "obj", objectType, testMethod)
        builder.addNode(objVar)
        builder.addEdge(DataFlowEdge(userVarId, objVarId, DataFlowKind.ASSIGN))

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(objVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis resolves CallSiteNode with concrete return type`() {
        val builder = DefaultGraph.Builder()

        val callee = method("com.example.Repo", "findUser", userType)
        val testMethod = method("com.example.Controller", "get", objectType)
        builder.addMethod(callee)
        builder.addMethod(testMethod)

        // Call site: repo.findUser() returns User (concrete type)
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, testMethod, callee, 10, null, emptyList())
        builder.addNode(cs)

        // Return
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)

        // Incoming edge from call site to return
        builder.addEdge(DataFlowEdge(csId, returnId, DataFlowKind.RETURN_VALUE))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
        assertTrue(result.returnStructures.any { it.className == "com.example.User" })
    }

    @Test
    fun `analysis traces argument type through incoming dataflow edges`() {
        val builder = DefaultGraph.Builder()

        val wrapperType = TypeDescriptor("com.example.Wrapper")
        val testMethod = method("com.example.Factory", "create", wrapperType)
        builder.addMethod(testMethod)

        // User is created and assigned to an intermediate variable
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)

        // Intermediate variable: obj (type Object, not concrete)
        val objVarId = NodeId.next()
        val objVar = LocalVariable(objVarId, "obj", objectType, testMethod)
        builder.addNode(objVar)
        builder.addEdge(DataFlowEdge(userVarId, objVarId, DataFlowKind.ASSIGN))

        // Constructor: new Wrapper(obj) where obj <- user
        val ctorMethod = MethodDescriptor(wrapperType, "<init>", listOf(objectType), voidType)
        val ctorId = NodeId.next()
        val ctor = CallSiteNode(ctorId, testMethod, ctorMethod, 10, null, listOf(objVarId))
        builder.addNode(ctor)

        // Return wrapper
        val wrapperVarId = NodeId.next()
        val wrapperVar = LocalVariable(wrapperVarId, "wrapper", wrapperType, testMethod)
        builder.addNode(wrapperVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(wrapperVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis resolves argument type from call site return type`() {
        val builder = DefaultGraph.Builder()

        val wrapperType = TypeDescriptor("com.example.Wrapper")
        val testMethod = method("com.example.Factory", "create", wrapperType)
        builder.addMethod(testMethod)

        // Call site: repo.findUser() returns User
        val findUserMethod = method("com.example.Repo", "findUser", userType)
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, testMethod, findUserMethod, 5, null, emptyList())
        builder.addNode(cs)

        // Constructor: new Wrapper(repo.findUser())
        val ctorMethod = MethodDescriptor(wrapperType, "<init>", listOf(objectType), voidType)
        val ctorCallId = NodeId.next()
        val ctorCall = CallSiteNode(ctorCallId, testMethod, ctorMethod, 10, null, listOf(csId))
        builder.addNode(ctorCall)

        // Return wrapper
        val wrapperVarId = NodeId.next()
        val wrapperVar = LocalVariable(wrapperVarId, "wrapper", wrapperType, testMethod)
        builder.addNode(wrapperVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(wrapperVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis traces through other node types via else branch`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", objectType)
        builder.addMethod(testMethod)

        // ParameterNode → dataflow → ReturnNode
        val paramId = NodeId.next()
        val param = ParameterNode(paramId, 0, objectType, testMethod)
        builder.addNode(param)

        // User var flows into param
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)
        builder.addEdge(DataFlowEdge(userVarId, paramId, DataFlowKind.ASSIGN))

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(paramId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should trace through ParameterNode (else branch) back to User
        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis traces through findArgumentType incoming edges`() {
        val builder = DefaultGraph.Builder()

        val genericType = TypeDescriptor("com.example.Container")
        val testMethod = method("com.example.Factory", "create", genericType)
        builder.addMethod(testMethod)

        // Chain: userVar -> objectVar -> constructorArg
        // findArgumentType(objectVar) should trace incoming to find userType
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)

        val objectVarId = NodeId.next()
        val objectVar = LocalVariable(objectVarId, "obj", objectType, testMethod)
        builder.addNode(objectVar)
        builder.addEdge(DataFlowEdge(userVarId, objectVarId, DataFlowKind.ASSIGN))

        // Constructor call for Container taking the objectVar as argument
        val initMethod = MethodDescriptor(genericType, "<init>", listOf(objectType), voidType)
        val initCallId = NodeId.next()
        val initCall = CallSiteNode(initCallId, testMethod, initMethod, 5, NodeId.next(), listOf(objectVarId))
        builder.addNode(initCall)

        // Container has a field that matches the constructor arg type
        val dataFieldDesc = FieldDescriptor(genericType, "data", objectType)
        builder.addNode(FieldNode(NodeId.next(), dataFieldDesc, isStatic = false))

        val containerVarId = NodeId.next()
        val containerVar = LocalVariable(containerVarId, "container", genericType, testMethod)
        builder.addNode(containerVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(containerVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }
}
