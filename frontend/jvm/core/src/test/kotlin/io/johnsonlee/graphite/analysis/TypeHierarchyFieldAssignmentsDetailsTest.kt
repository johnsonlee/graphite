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

internal class TypeHierarchyFieldAssignmentsDetailsTest : TypeHierarchyAnalysisTestSupport() {
    @Test
    fun `global field assignment tracking traces through Object-typed locals`() {
        val builder = DefaultGraph.Builder()

        val setupMethod = method("com.example.Service", "setup", voidType)
        builder.addMethod(setupMethod)

        // Object-typed local with incoming edge from User
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, setupMethod)
        builder.addNode(userVar)

        val objVarId = NodeId.next()
        val objVar = LocalVariable(objVarId, "obj", objectType, setupMethod)
        builder.addNode(objVar)
        builder.addEdge(DataFlowEdge(userVarId, objVarId, DataFlowKind.ASSIGN))

        // Setter call: response.setData(obj) - obj is Object type
        val setDataMethod = MethodDescriptor(responseType, "setData", listOf(objectType), voidType)
        val setDataId = NodeId.next()
        val setData = CallSiteNode(setDataId, setupMethod, setDataMethod, 10, null, listOf(objVarId))
        builder.addNode(setData)

        // Another method returns response
        val getMethod = method("com.example.Controller", "get", responseType)
        builder.addMethod(getMethod)

        val responseVarId = NodeId.next()
        val responseVar = LocalVariable(responseVarId, "resp", responseType, getMethod)
        builder.addNode(responseVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, getMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(responseVarId, returnId, DataFlowKind.ASSIGN))

        // Field in response: data
        val dataFieldDesc = FieldDescriptor(responseType, "data", objectType)
        builder.addNode(FieldNode(NodeId.next(), dataFieldDesc, isStatic = false))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(getMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `global field assignment tracking handles CallSiteNode return type`() {
        val builder = DefaultGraph.Builder()

        val setupMethod = method("com.example.Service", "setup", voidType)
        builder.addMethod(setupMethod)

        // Call site returns User
        val findUserMethod = method("com.example.Repo", "findUser", userType)
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, setupMethod, findUserMethod, 5, null, emptyList())
        builder.addNode(cs)

        // Setter call: response.setData(repo.findUser())
        val setDataMethod = MethodDescriptor(responseType, "setData", listOf(objectType), voidType)
        val setDataId = NodeId.next()
        val setData = CallSiteNode(setDataId, setupMethod, setDataMethod, 10, null, listOf(csId))
        builder.addNode(setData)

        val getMethod = method("com.example.Controller", "get", responseType)
        builder.addMethod(getMethod)

        val responseVarId = NodeId.next()
        val responseVar = LocalVariable(responseVarId, "resp", responseType, getMethod)
        builder.addNode(responseVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, getMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(responseVarId, returnId, DataFlowKind.ASSIGN))

        val dataFieldDesc = FieldDescriptor(responseType, "data", objectType)
        builder.addNode(FieldNode(NodeId.next(), dataFieldDesc, isStatic = false))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(getMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `global field assignment tracking handles else branch in findTypesRecursive`() {
        val builder = DefaultGraph.Builder()

        val setupMethod = method("com.example.Service", "setup", voidType)
        builder.addMethod(setupMethod)

        // ParameterNode (not LocalVariable, not CallSiteNode) with incoming edge from User
        val paramId = NodeId.next()
        val param = ParameterNode(paramId, 0, objectType, setupMethod)
        builder.addNode(param)

        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, setupMethod)
        builder.addNode(userVar)
        builder.addEdge(DataFlowEdge(userVarId, paramId, DataFlowKind.ASSIGN))

        // Setter call: response.setData(param)
        val setDataMethod = MethodDescriptor(responseType, "setData", listOf(objectType), voidType)
        val setDataId = NodeId.next()
        val setData = CallSiteNode(setDataId, setupMethod, setDataMethod, 10, null, listOf(paramId))
        builder.addNode(setData)

        val getMethod = method("com.example.Controller", "get", responseType)
        builder.addMethod(getMethod)

        val responseVarId = NodeId.next()
        val responseVar = LocalVariable(responseVarId, "resp", responseType, getMethod)
        builder.addNode(responseVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, getMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(responseVarId, returnId, DataFlowKind.ASSIGN))

        val dataFieldDesc = FieldDescriptor(responseType, "data", objectType)
        builder.addNode(FieldNode(NodeId.next(), dataFieldDesc, isStatic = false))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(getMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis traces through FieldNode with analyzable declared type but no global assignments`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", objectType)
        builder.addMethod(testMethod)

        // Field: Container.data of type User (analyzable, not Object)
        val containerType = TypeDescriptor("com.example.Container")
        val dataFieldDesc = FieldDescriptor(containerType, "data", userType)
        val dataFieldId = NodeId.next()
        val dataField = FieldNode(dataFieldId, dataFieldDesc, isStatic = false)
        builder.addNode(dataField)

        // Return from field load
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(dataFieldId, returnId, DataFlowKind.FIELD_LOAD))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should find User type from field's declared type
        assertTrue(result.returnStructures.isNotEmpty())
        assertTrue(result.returnStructures.any { it.className == "com.example.User" })
    }

    @Test
    fun `analysis finds all setter calls for type including fallback search`() {
        val builder = DefaultGraph.Builder()

        val emptyType = TypeDescriptor("com.example.Empty")
        val testMethod = method("com.example.Controller", "build", emptyType)
        builder.addMethod(testMethod)

        // No field nodes, no getter methods for Empty - so strategies 1-5 find nothing
        // Strategy 7 (findAllSetterCallsForType) kicks in when fields.isEmpty()

        // Setter call with receiver type matching emptyType
        val receiverVarId = NodeId.next()
        val receiverVar = LocalVariable(receiverVarId, "obj", emptyType, testMethod)
        builder.addNode(receiverVar)

        val argId = NodeId.next()
        val arg = LocalVariable(argId, "name", stringType, testMethod)
        builder.addNode(arg)

        // Setter from declaring class matching emptyType
        val setNameMethod = MethodDescriptor(emptyType, "setName", listOf(stringType), voidType)
        val setNameId = NodeId.next()
        val setNameCall = CallSiteNode(setNameId, testMethod, setNameMethod, 10, receiverVarId, listOf(argId))
        builder.addNode(setNameCall)

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(receiverVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `findAllSetterCallsForType matches receiver by declaring class`() {
        val builder = DefaultGraph.Builder()

        val dtoType = TypeDescriptor("com.example.Dto")
        val testMethod = method("com.example.Builder", "build", dtoType)
        builder.addMethod(testMethod)

        // Receiver is of a different local type but callee.declaringClass matches
        val superType = TypeDescriptor("com.example.BaseDto")
        val receiverVarId = NodeId.next()
        val receiverVar = LocalVariable(receiverVarId, "base", superType, testMethod)
        builder.addNode(receiverVar)

        val argId = NodeId.next()
        val argVar = LocalVariable(argId, "v", stringType, testMethod)
        builder.addNode(argVar)

        // Setter call where declaringClass == dtoType (matches via second condition)
        val setValueMethod = MethodDescriptor(dtoType, "setValue", listOf(stringType), voidType)
        val setValueId = NodeId.next()
        val setValueCall = CallSiteNode(setValueId, testMethod, setValueMethod, 10, receiverVarId, listOf(argId))
        builder.addNode(setValueCall)

        val dtoVarId = NodeId.next()
        val dtoVar = LocalVariable(dtoVarId, "dto", dtoType, testMethod)
        builder.addNode(dtoVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(dtoVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `inferDeclaredTypeFromSetter falls back to Object when no field or params`() {
        val builder = DefaultGraph.Builder()

        val dtoType = TypeDescriptor("com.example.Dto")
        val testMethod = method("com.example.Builder", "build", dtoType)
        builder.addMethod(testMethod)

        // Setter call with no arguments and no matching field
        val setFooMethod = MethodDescriptor(dtoType, "setFoo", emptyList(), voidType)
        val setFooId = NodeId.next()
        val receiverVarId = NodeId.next()
        val receiverVar = LocalVariable(receiverVarId, "dto", dtoType, testMethod)
        builder.addNode(receiverVar)
        val setFooCall = CallSiteNode(setFooId, testMethod, setFooMethod, 10, receiverVarId, emptyList())
        builder.addNode(setFooCall)

        val dtoVarId = NodeId.next()
        val dtoVar = LocalVariable(dtoVarId, "result", dtoType, testMethod)
        builder.addNode(dtoVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(dtoVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should not crash, setter with no params results in Object fallback
        assertTrue(result.returnStructures.isNotEmpty())
    }
}
