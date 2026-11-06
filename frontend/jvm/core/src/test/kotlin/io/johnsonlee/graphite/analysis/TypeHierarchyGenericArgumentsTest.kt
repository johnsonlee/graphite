package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeHierarchyConfig
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TypeHierarchyGenericArgumentsTest : TypeHierarchyAnalysisTestSupport() {
    @Test
    fun `analysis discovers generic type arguments from constructors`() {
        val builder = DefaultGraph.Builder()

        val wrapperType = TypeDescriptor("com.example.Wrapper")
        val createMethod = method("com.example.Factory", "create", wrapperType)
        builder.addMethod(createMethod)

        // User local variable as constructor arg
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, createMethod)
        builder.addNode(userVar)

        // Wrapper constructor call: new Wrapper(user)
        val ctorMethod = MethodDescriptor(wrapperType, "<init>", listOf(objectType), voidType)
        val ctorId = NodeId.next()
        val ctor = CallSiteNode(ctorId, createMethod, ctorMethod, 10, null, listOf(userVarId))
        builder.addNode(ctor)

        // Wrapper local variable
        val wrapperVarId = NodeId.next()
        val wrapperVar = LocalVariable(wrapperVarId, "wrapper", wrapperType, createMethod)
        builder.addNode(wrapperVar)

        // Return node
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, createMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(wrapperVarId, returnId, DataFlowKind.ASSIGN))

        // Field in Wrapper
        val innerFieldDesc = FieldDescriptor(wrapperType, "inner", objectType)
        val innerFieldId = NodeId.next()
        val innerField = FieldNode(innerFieldId, innerFieldDesc, isStatic = false)
        builder.addNode(innerField)

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(createMethod)

        assertEquals("create", result.method.name)
        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis matches constructor arguments to fields`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "create", userType)
        builder.addMethod(testMethod)

        // User has a field: address of type Address
        val addressFieldDesc = FieldDescriptor(userType, "address", addressType)
        val addressFieldId = NodeId.next()
        val addressField = FieldNode(addressFieldId, addressFieldDesc, isStatic = false)
        builder.addNode(addressField)

        // Address local variable
        val addressVarId = NodeId.next()
        val addressVar = LocalVariable(addressVarId, "addr", addressType, testMethod)
        builder.addNode(addressVar)

        // Constructor call: new User(addr)
        val ctorMethod = MethodDescriptor(userType, "<init>", listOf(addressType), voidType)
        val ctorId = NodeId.next()
        val ctor = CallSiteNode(ctorId, testMethod, ctorMethod, 10, null, listOf(addressVarId))
        builder.addNode(ctor)

        // User local variable
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)

        // Return
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis merges constructor fields with setter fields`() {
        val builder = DefaultGraph.Builder()

        val containerType = TypeDescriptor("com.example.Container")
        val testMethod = method("com.example.Controller", "create", containerType)
        builder.addMethod(testMethod)

        // Container has a field: data of type Object
        val dataFieldDesc = FieldDescriptor(containerType, "data", objectType)
        builder.addNode(FieldNode(NodeId.next(), dataFieldDesc, isStatic = false))

        // User var for both setter and constructor
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)

        // Setter call: container.setData(user)
        val setDataMethod = MethodDescriptor(containerType, "setData", listOf(objectType), voidType)
        val containerVarId = NodeId.next()
        val containerVar = LocalVariable(containerVarId, "container", containerType, testMethod)
        builder.addNode(containerVar)
        val setDataId = NodeId.next()
        val setDataCall = CallSiteNode(setDataId, testMethod, setDataMethod, 10, containerVarId, listOf(userVarId))
        builder.addNode(setDataCall)

        // Constructor call: new Container(user)
        val ctorMethod = MethodDescriptor(containerType, "<init>", listOf(objectType), voidType)
        val ctorId = NodeId.next()
        val ctor = CallSiteNode(ctorId, testMethod, ctorMethod, 15, null, listOf(userVarId))
        builder.addNode(ctor)

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

    @Test
    fun `constructorAssignment merges with existing setter-discovered fields`() {
        val builder = DefaultGraph.Builder()

        val dataType = TypeDescriptor("com.example.Data")
        val testMethod = method("com.example.Factory", "create", dataType)
        builder.addMethod(testMethod)

        // Field on Data type
        val nameFieldDesc = FieldDescriptor(dataType, "name", stringType)
        builder.addNode(FieldNode(NodeId.next(), nameFieldDesc, isStatic = false))

        // Setter call first (Strategy 1 finds it)
        val setterArgId = NodeId.next()
        val setterArg = LocalVariable(setterArgId, "n1", stringType, testMethod)
        builder.addNode(setterArg)

        val setNameMethod = MethodDescriptor(dataType, "setName", listOf(stringType), voidType)
        val setNameId = NodeId.next()
        val setNameCall = CallSiteNode(setNameId, testMethod, setNameMethod, 5, NodeId.next(), listOf(setterArgId))
        builder.addNode(setNameCall)

        // Constructor call with same field (Strategy 3 merges with existing)
        val ctorArgId = NodeId.next()
        val ctorArg = LocalVariable(ctorArgId, "n2", stringType, testMethod)
        builder.addNode(ctorArg)

        val initMethod = MethodDescriptor(dataType, "<init>", listOf(stringType), voidType)
        val initCallId = NodeId.next()
        val initCall = CallSiteNode(initCallId, testMethod, initMethod, 3, NodeId.next(), listOf(ctorArgId))
        builder.addNode(initCall)

        val dataVarId = NodeId.next()
        val dataVar = LocalVariable(dataVarId, "d", dataType, testMethod)
        builder.addNode(dataVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(dataVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `constructorAssignment uses isCompatibleType for java types`() {
        val builder = DefaultGraph.Builder()

        val wrapperType = TypeDescriptor("com.example.Wrapper")
        val testMethod = method("com.example.Factory", "wrap", wrapperType)
        builder.addMethod(testMethod)

        // Field of type java.util.List
        val listType = TypeDescriptor("java.util.List")
        val itemsFieldDesc = FieldDescriptor(wrapperType, "items", listType)
        builder.addNode(FieldNode(NodeId.next(), itemsFieldDesc, isStatic = false))

        // Constructor arg of type java.util.ArrayList - compatible via isCompatibleType
        val arrayListType = TypeDescriptor("java.util.ArrayList")
        val argId = NodeId.next()
        val argVar = LocalVariable(argId, "list", arrayListType, testMethod)
        builder.addNode(argVar)

        val initMethod = MethodDescriptor(wrapperType, "<init>", listOf(listType), voidType)
        val initCallId = NodeId.next()
        val initCall = CallSiteNode(initCallId, testMethod, initMethod, 3, NodeId.next(), listOf(argId))
        builder.addNode(initCall)

        val wrapperVarId = NodeId.next()
        val wrapperVar = LocalVariable(wrapperVarId, "w", wrapperType, testMethod)
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
}
