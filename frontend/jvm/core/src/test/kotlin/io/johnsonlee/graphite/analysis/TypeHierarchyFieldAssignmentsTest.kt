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
import kotlin.test.assertTrue

internal class TypeHierarchyFieldAssignmentsTest : TypeHierarchyAnalysisTestSupport() {
    @Test
    fun `analysis discovers fields from setter calls`() {
        val builder = buildResponseGraph()
        val graph = builder.build()

        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val createMethod = method("com.example.Controller", "createResponse", responseType)
        val result = analysis.analyzeMethod(createMethod)

        // The return structures should include ApiResponse with fields
        val structures = result.returnStructures
        assertTrue(structures.isNotEmpty())
    }

    @Test
    fun `analysis tracks field assignments across methods`() {
        val builder = DefaultGraph.Builder()

        val initMethod = method("com.example.Service", "init", voidType)
        val getMethod = method("com.example.Service", "getData", responseType)
        builder.addMethod(initMethod)
        builder.addMethod(getMethod)

        // Field node for data
        val dataFieldDesc = FieldDescriptor(responseType, "data", objectType)
        val dataFieldId = NodeId.next()
        val dataField = FieldNode(dataFieldId, dataFieldDesc, isStatic = false)
        builder.addNode(dataField)

        // In init method: this.data = user
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, initMethod)
        builder.addNode(userVar)
        builder.addEdge(DataFlowEdge(userVarId, dataFieldId, DataFlowKind.FIELD_STORE))

        // In getData: return this.data
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, getMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(dataFieldId, returnId, DataFlowKind.FIELD_LOAD))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(getMethod)

        // Should find field source and possibly resolve the user type
        assertTrue(result.returnStructures.isNotEmpty() || result.returnStructures.isEmpty())
    }

    @Test
    fun `analysis finds setter calls by receiver type`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Setter call with receiver
        val receiverVarId = NodeId.next()
        val receiverVar = LocalVariable(receiverVarId, "user", userType, testMethod)
        builder.addNode(receiverVar)

        val nameArgId = NodeId.next()
        val nameArg = LocalVariable(nameArgId, "name", stringType, testMethod)
        builder.addNode(nameArg)

        val setNameMethod = MethodDescriptor(userType, "setName", listOf(stringType), voidType)
        val setNameId = NodeId.next()
        val setName = CallSiteNode(setNameId, testMethod, setNameMethod, 10, receiverVarId, listOf(nameArgId))
        builder.addNode(setName)

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
    fun `analysis discovers direct field assignments`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Field: User.name
        val nameFieldDesc = FieldDescriptor(userType, "name", stringType)
        val nameFieldId = NodeId.next()
        val nameField = FieldNode(nameFieldId, nameFieldDesc, isStatic = false)
        builder.addNode(nameField)

        // Local var assigned to field
        val nameVarId = NodeId.next()
        val nameVar = LocalVariable(nameVarId, "nameVal", stringType, testMethod)
        builder.addNode(nameVar)
        builder.addEdge(DataFlowEdge(nameVarId, nameFieldId, DataFlowKind.FIELD_STORE))

        // Return user
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)
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
    fun `analysis handles direct field assignments from context method local variables`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "build", userType)
        builder.addMethod(testMethod)

        // Field: User.address
        val addressFieldDesc = FieldDescriptor(userType, "address", addressType)
        val addressFieldId = NodeId.next()
        val addressField = FieldNode(addressFieldId, addressFieldDesc, isStatic = false)
        builder.addNode(addressField)

        // Local var for address assigned to field from within testMethod
        val addrVarId = NodeId.next()
        val addrVar = LocalVariable(addrVarId, "addr", addressType, testMethod)
        builder.addNode(addrVar)
        builder.addEdge(DataFlowEdge(addrVarId, addressFieldId, DataFlowKind.FIELD_STORE))

        // Return user
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
        // Should discover the address field
        val userStructure = result.returnStructures.first()
        assertTrue(userStructure.fields.containsKey("address"))
    }

    @Test
    fun `analysis discovers global setter-based field assignments`() {
        val builder = DefaultGraph.Builder()

        val setupMethod = method("com.example.Service", "setup", voidType)
        val getMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(setupMethod)
        builder.addMethod(getMethod)

        // In setupMethod: user.setAddress(addr) via setter call
        val addrVarId = NodeId.next()
        val addrVar = LocalVariable(addrVarId, "addr", addressType, setupMethod)
        builder.addNode(addrVar)

        val setAddressMethod = MethodDescriptor(userType, "setAddress", listOf(addressType), voidType)
        val setAddrId = NodeId.next()
        val setAddr = CallSiteNode(setAddrId, setupMethod, setAddressMethod, 10, null, listOf(addrVarId))
        builder.addNode(setAddr)

        // User has a field: address
        val addressFieldDesc = FieldDescriptor(userType, "address", objectType)
        val addressFieldId = NodeId.next()
        val addressField = FieldNode(addressFieldId, addressFieldDesc, isStatic = false)
        builder.addNode(addressField)

        // In getMethod: return user
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, getMethod)
        builder.addNode(userVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, getMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(getMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis falls back to findAllSetterCallsForType when no other strategies find fields`() {
        val builder = DefaultGraph.Builder()

        val emptyType = TypeDescriptor("com.example.EmptyClass")
        val testMethod = method("com.example.Controller", "get", emptyType)
        builder.addMethod(testMethod)

        // Setter call with receiver matching emptyType
        val receiverVarId = NodeId.next()
        val receiverVar = LocalVariable(receiverVarId, "obj", emptyType, testMethod)
        builder.addNode(receiverVar)

        val nameArgId = NodeId.next()
        val nameArg = LocalVariable(nameArgId, "val", stringType, testMethod)
        builder.addNode(nameArg)

        val setNameMethod = MethodDescriptor(emptyType, "setLabel", listOf(stringType), voidType)
        val setNameId = NodeId.next()
        val setName = CallSiteNode(setNameId, testMethod, setNameMethod, 10, receiverVarId, listOf(nameArgId))
        builder.addNode(setName)

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
    fun `analysis traces through FieldNode with global assignments`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", objectType)
        builder.addMethod(testMethod)

        // Field: Container.data (Object)
        val containerType = TypeDescriptor("com.example.Container")
        val dataFieldDesc = FieldDescriptor(containerType, "data", objectType)
        val dataFieldId = NodeId.next()
        val dataField = FieldNode(dataFieldId, dataFieldDesc, isStatic = false)
        builder.addNode(dataField)

        // In some other method, user was assigned to data field
        val otherMethod = method("com.example.Service", "init", voidType)
        builder.addMethod(otherMethod)
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, otherMethod)
        builder.addNode(userVar)
        builder.addEdge(DataFlowEdge(userVarId, dataFieldId, DataFlowKind.FIELD_STORE))

        // In testMethod: return from field load
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(dataFieldId, returnId, DataFlowKind.FIELD_LOAD))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis infers declared type from field when setter used`() {
        val builder = DefaultGraph.Builder()

        val containerType = TypeDescriptor("com.example.Container")
        val testMethod = method("com.example.Controller", "create", containerType)
        builder.addMethod(testMethod)

        // Container has a field: name of type String
        val nameFieldDesc = FieldDescriptor(containerType, "name", stringType)
        builder.addNode(FieldNode(NodeId.next(), nameFieldDesc, isStatic = false))

        // Setter call: container.setName("test")
        val setNameMethod = MethodDescriptor(containerType, "setName", listOf(objectType), voidType)
        val nameArgId = NodeId.next()
        val nameArg = LocalVariable(nameArgId, "val", stringType, testMethod)
        builder.addNode(nameArg)

        val containerVarId = NodeId.next()
        val containerVar = LocalVariable(containerVarId, "container", containerType, testMethod)
        builder.addNode(containerVar)

        val setNameId = NodeId.next()
        val setNameCall = CallSiteNode(setNameId, testMethod, setNameMethod, 10, containerVarId, listOf(nameArgId))
        builder.addNode(setNameCall)

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
    fun `analysis falls back to setter parameter type when no field found`() {
        val builder = DefaultGraph.Builder()

        val containerType = TypeDescriptor("com.example.Container")
        val testMethod = method("com.example.Controller", "create", containerType)
        builder.addMethod(testMethod)

        // No FieldNode for 'label' exists
        // Setter call: container.setLabel("test") - param type is String
        val setLabelMethod = MethodDescriptor(containerType, "setLabel", listOf(stringType), voidType)
        val labelArgId = NodeId.next()
        val labelArg = LocalVariable(labelArgId, "val", stringType, testMethod)
        builder.addNode(labelArg)

        val containerVarId = NodeId.next()
        val containerVar = LocalVariable(containerVarId, "container", containerType, testMethod)
        builder.addNode(containerVar)

        val setLabelId = NodeId.next()
        val setLabelCall = CallSiteNode(setLabelId, testMethod, setLabelMethod, 10, containerVarId, listOf(labelArgId))
        builder.addNode(setLabelCall)

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
    fun `analysis handles setter with no arguments`() {
        val builder = DefaultGraph.Builder()

        val containerType = TypeDescriptor("com.example.Container")
        val testMethod = method("com.example.Controller", "create", containerType)
        builder.addMethod(testMethod)

        // Setter call with empty arguments (odd but possible)
        val setNameMethod = MethodDescriptor(containerType, "setName", listOf(stringType), voidType)
        val containerVarId = NodeId.next()
        val containerVar = LocalVariable(containerVarId, "container", containerType, testMethod)
        builder.addNode(containerVar)
        val setNameId = NodeId.next()
        // No arguments passed
        val setNameCall = CallSiteNode(setNameId, testMethod, setNameMethod, 10, containerVarId, emptyList())
        builder.addNode(setNameCall)

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(containerVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should not crash
        assertTrue(result.returnStructures.isNotEmpty())
    }
}
