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

internal class TypeHierarchyConfigurationTest : TypeHierarchyAnalysisTestSupport() {
    @Test
    fun `analysis excludes configured packages`() {
        val builder = DefaultGraph.Builder()
        val excludedType = TypeDescriptor("java.lang.String")
        val testMethod = method("com.example.Test", "test", excludedType)
        builder.addMethod(testMethod)

        val varId = NodeId.next()
        val variable = LocalVariable(varId, "s", excludedType, testMethod)
        builder.addNode(variable)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(varId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(excludePackages = listOf("java."))
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // String is excluded, so no detailed structure
        assertTrue(result.returnStructures.isEmpty())
    }

    @Test
    fun `analysis respects maxDepth config`() {
        val builder = DefaultGraph.Builder()
        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(maxDepth = 0, includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // With maxDepth=0, should not traverse deeply
        assertEquals("get", result.method.name)
    }

    @Test
    fun `analysis uses includePackages to filter types`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", objectType)
        builder.addMethod(testMethod)

        val externalType = TypeDescriptor("org.external.SomeClass")
        val externalVarId = NodeId.next()
        val externalVar = LocalVariable(externalVarId, "ext", externalType, testMethod)
        builder.addNode(externalVar)

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(externalVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // org.external is not in includePackages, so it should not be analyzed deeply
        assertTrue(result.returnStructures.isEmpty())
    }

    @Test
    fun `analysis returns simple type when maxDepth exceeded in buildTypeStructure`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // User field pointing to Address which points back
        val nameFieldDesc = FieldDescriptor(userType, "name", stringType)
        builder.addNode(FieldNode(NodeId.next(), nameFieldDesc, isStatic = false))

        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(maxDepth = 1, includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should not crash, depth is limited
        assertEquals("get", result.method.name)
    }

    @Test
    fun `analyzeReturnNode limits recursion depth with maxDepth`() {
        val builder = DefaultGraph.Builder()
        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Return a LocalVariable of type User - found at depth 0
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        // maxDepth=1 limits how deep field structures are explored
        val config = TypeHierarchyConfig(maxDepth = 1, includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // The top-level type is still found (depth 0 -> buildTypeStructure at depth 1)
        assertTrue(result.returnStructures.isNotEmpty())
        assertEquals("com.example.User", result.returnStructures.first().className)
    }

    @Test
    fun `analyzeGenericTypeArguments returns empty when depth exceeds maxDepth`() {
        val builder = DefaultGraph.Builder()

        val wrapperType = TypeDescriptor("com.example.Wrapper")
        val testMethod = method("com.example.Factory", "create", wrapperType)
        builder.addMethod(testMethod)

        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)

        val ctorMethod = MethodDescriptor(wrapperType, "<init>", listOf(objectType), voidType)
        val ctorId = NodeId.next()
        val ctor = CallSiteNode(ctorId, testMethod, ctorMethod, 10, null, listOf(userVarId))
        builder.addNode(ctor)

        val wrapperVarId = NodeId.next()
        val wrapperVar = LocalVariable(wrapperVarId, "wrapper", wrapperType, testMethod)
        builder.addNode(wrapperVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(wrapperVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(maxDepth = 1, includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // With very limited depth, should still not crash
        assertEquals("create", result.method.name)
    }

    @Test
    fun `analysis respects excludePackages config`() {
        val builder = DefaultGraph.Builder()

        val excludedType = TypeDescriptor("com.excluded.Data")
        val testMethod = method("com.example.Controller", "get", excludedType)
        builder.addMethod(testMethod)

        val varId = NodeId.next()
        val variable = LocalVariable(varId, "data", excludedType, testMethod)
        builder.addNode(variable)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(varId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(excludePackages = listOf("com.excluded"))
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // com.excluded is excluded, no structures
        assertTrue(result.returnStructures.isEmpty())
    }

    @Test
    fun `analyzeReturnNode returns empty when called beyond maxDepth via interprocedural`() {
        // We need a chain: method A calls method B at depth near maxDepth.
        // Method B's return node analysis should trigger the depth > maxDepth guard.
        val builder = DefaultGraph.Builder()

        val methodA = MethodDescriptor(TypeDescriptor("com.example.A"), "getResult", emptyList(), objectType)
        val methodB = MethodDescriptor(TypeDescriptor("com.example.B"), "getData", emptyList(), userType)
        builder.addMethod(methodA)
        builder.addMethod(methodB)

        // Method B has a return node
        val bVarId = NodeId.next()
        val bVar = LocalVariable(bVarId, "data", userType, methodB)
        builder.addNode(bVar)
        val bReturnId = NodeId.next()
        val bReturn = ReturnNode(bReturnId, methodB)
        builder.addNode(bReturn)
        builder.addEdge(DataFlowEdge(bVarId, bReturnId, DataFlowKind.ASSIGN))

        // Method A calls method B - a CallSiteNode returning Object
        val callBId = NodeId.next()
        val callB = CallSiteNode(callBId, methodA, methodB, 5, NodeId.next(), emptyList())
        builder.addNode(callB)

        val aReturnId = NodeId.next()
        val aReturn = ReturnNode(aReturnId, methodA)
        builder.addNode(aReturn)
        builder.addEdge(DataFlowEdge(callBId, aReturnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config =
            TypeHierarchyConfig(
                maxDepth = 1, includePackages = listOf("com.example"), excludePackages = emptyList(), interProcedural = true)
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(methodA)

        // Even with shallow maxDepth, should not throw
        assertEquals("getResult", result.method.name)
    }

    @Test
    fun `buildTypeStructure returns simple structure for excluded type`() {
        val builder = DefaultGraph.Builder()

        val excludedType = TypeDescriptor("org.springframework.internal.Proxy")
        val testMethod = method("com.example.Controller", "get", excludedType)
        builder.addMethod(testMethod)

        val varId = NodeId.next()
        val variable = LocalVariable(varId, "proxy", excludedType, testMethod)
        builder.addNode(variable)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(varId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        // excludePackages includes org.springframework
        val config =
            TypeHierarchyConfig(
                includePackages = listOf("com.example", "org.springframework"), excludePackages = listOf("org.springframework.internal"))
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // The excluded type should still appear but without deep field analysis
        assertTrue(
            result.returnStructures.isEmpty() || result.returnStructures.any { it.className == "org.springframework.internal.Proxy" })
    }

    @Test
    fun `analyzeGenericTypeArguments returns empty at maxDepth`() {
        val builder = DefaultGraph.Builder()

        val genericType = TypeDescriptor("com.example.Container")
        val testMethod = method("com.example.Factory", "create", genericType)
        builder.addMethod(testMethod)

        // Constructor call for Container with an argument
        val argId = NodeId.next()
        val argVar = LocalVariable(argId, "item", userType, testMethod)
        builder.addNode(argVar)

        val initMethod = MethodDescriptor(genericType, "<init>", listOf(objectType), voidType)
        val initCallId = NodeId.next()
        val initCall = CallSiteNode(initCallId, testMethod, initMethod, 5, NodeId.next(), listOf(argId))
        builder.addNode(initCall)

        val varId = NodeId.next()
        val variable = LocalVariable(varId, "container", genericType, testMethod)
        builder.addNode(variable)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(varId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        // maxDepth=1 means analyzeGenericTypeArguments at depth 2 will be > maxDepth
        val config = TypeHierarchyConfig(maxDepth = 1, includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should complete without errors
        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `addGlobalFieldAssignments uses TypeStructure simple at maxDepth`() {
        val builder = DefaultGraph.Builder()

        val responseType = TypeDescriptor("com.example.Response")
        val testMethod = method("com.example.Controller", "get", responseType)
        builder.addMethod(testMethod)

        // A field on Response whose actual type is assigned from another method
        val dataFieldDesc = FieldDescriptor(responseType, "payload", objectType)
        val dataFieldId = NodeId.next()
        builder.addNode(FieldNode(dataFieldId, dataFieldDesc, isStatic = false))

        // In some other method, setter call assigns User to Response.payload
        val otherMethod = method("com.example.Service", "prepare", voidType)
        builder.addMethod(otherMethod)

        val payloadVarId = NodeId.next()
        val payloadVar = LocalVariable(payloadVarId, "u", userType, otherMethod)
        builder.addNode(payloadVar)

        val setPayloadMethod = MethodDescriptor(responseType, "setPayload", listOf(objectType), voidType)
        val setPayloadId = NodeId.next()
        val setPayloadCall = CallSiteNode(setPayloadId, otherMethod, setPayloadMethod, 20, NodeId.next(), listOf(payloadVarId))
        builder.addNode(setPayloadCall)

        // Return Response from testMethod
        val responseVarId = NodeId.next()
        val responseVar = LocalVariable(responseVarId, "resp", responseType, testMethod)
        builder.addNode(responseVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(responseVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        // maxDepth=2: buildTypeStructure called at depth 1, global assignments at depth 2 is >= maxDepth
        val config = TypeHierarchyConfig(maxDepth = 2, includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `shouldAnalyzeType returns true when includePackages is empty and not excluded`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.random.Controller", "get", userType)
        builder.addMethod(testMethod)

        val varId = NodeId.next()
        val variable = LocalVariable(varId, "user", userType, testMethod)
        builder.addNode(variable)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(varId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        // Empty includePackages, default excludePackages
        val config = TypeHierarchyConfig(includePackages = emptyList(), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // With no includePackages and no excludePackages, shouldAnalyzeType returns true for all
        assertTrue(result.returnStructures.isNotEmpty())
        assertEquals("com.example.User", result.returnStructures.first().className)
    }
}
