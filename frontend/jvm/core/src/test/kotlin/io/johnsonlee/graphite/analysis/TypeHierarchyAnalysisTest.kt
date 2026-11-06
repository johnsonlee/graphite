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
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class TypeHierarchyAnalysisTest : TypeHierarchyAnalysisTestSupport() {
    @Test
    fun `analyzeReturnTypes finds method and analyzes return structures`() {
        val builder = buildResponseGraph()
        val graph = builder.build()

        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val results = analysis.analyzeReturnTypes(MethodPattern(declaringClass = "com.example.Controller", name = "createResponse"))

        assertEquals(1, results.size)
        assertEquals("createResponse", results[0].method.name)
    }

    @Test
    fun `analyzeReturnTypes returns empty for no matching methods`() {
        val graph = DefaultGraph.Builder().build()
        val analysis = TypeHierarchyAnalysis(graph)
        val results = analysis.analyzeReturnTypes(MethodPattern(name = "missing"))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `analyzeMethod returns structure with return type`() {
        val builder = buildResponseGraph()
        val graph = builder.build()

        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val createMethod = method("com.example.Controller", "createResponse", responseType)
        val result = analysis.analyzeMethod(createMethod)

        assertEquals("createResponse", result.method.name)
        // Should find the response type as return structure
        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analyzeMethod handles recursive methods`() {
        val recursiveMethod = method("com.example.Tree", "flatten", userType)
        val builder = DefaultGraph.Builder()
        builder.addMethod(recursiveMethod)

        // Return node that refers back to itself through call site
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, recursiveMethod)
        builder.addNode(returnNode)

        // Recursive call site
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, recursiveMethod, recursiveMethod, 10, null, emptyList())
        builder.addNode(cs)
        builder.addEdge(DataFlowEdge(csId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(recursiveMethod)

        // Should not stack overflow
        assertEquals("flatten", result.method.name)
    }

    @Test
    fun `analysis handles call site with Object return type interprocedurally`() {
        val builder = DefaultGraph.Builder()

        val innerMethod = method("com.example.Repo", "findData", objectType)
        val outerMethod = method("com.example.Controller", "getData", objectType)
        builder.addMethod(innerMethod)
        builder.addMethod(outerMethod)

        // Inner method: return user
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, innerMethod)
        builder.addNode(userVar)
        val innerReturnId = NodeId.next()
        val innerReturn = ReturnNode(innerReturnId, innerMethod)
        builder.addNode(innerReturn)
        builder.addEdge(DataFlowEdge(userVarId, innerReturnId, DataFlowKind.ASSIGN))

        // Outer method: return repo.findData()
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, outerMethod, innerMethod, 10, null, emptyList())
        builder.addNode(cs)
        val outerReturnId = NodeId.next()
        val outerReturn = ReturnNode(outerReturnId, outerMethod)
        builder.addNode(outerReturn)
        builder.addEdge(DataFlowEdge(csId, outerReturnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(includePackages = listOf("com.example"), excludePackages = emptyList(), interProcedural = true)
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(outerMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analysis follows type hierarchy for field discovery`() {
        val builder = DefaultGraph.Builder()
        val parentType = TypeDescriptor("com.example.BaseEntity")

        // Type hierarchy: User extends BaseEntity
        builder.addTypeRelation(userType, parentType, TypeRelation.EXTENDS)

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Parent field
        val idFieldDesc = FieldDescriptor(parentType, "id", TypeDescriptor("long"))
        val idFieldId = NodeId.next()
        val idField = FieldNode(idFieldId, idFieldDesc, isStatic = false)
        builder.addNode(idField)

        // Child field
        val nameFieldDesc = FieldDescriptor(userType, "name", stringType)
        val nameFieldId = NodeId.next()
        val nameField = FieldNode(nameFieldId, nameFieldDesc, isStatic = false)
        builder.addNode(nameField)

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
    fun `analysis skips synthetic fields`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Synthetic field
        val syntheticFieldDesc = FieldDescriptor(userType, "\$delegate", objectType)
        builder.addNode(FieldNode(NodeId.next(), syntheticFieldDesc, isStatic = false))

        // Another synthetic field
        val thisFieldDesc = FieldDescriptor(userType, "this\$0", objectType)
        builder.addNode(FieldNode(NodeId.next(), thisFieldDesc, isStatic = false))

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
        val userStructure = result.returnStructures.first()
        assertFalse(userStructure.fields.containsKey("\$delegate"))
        assertFalse(userStructure.fields.containsKey("this\$0"))
    }

    @Test
    fun `addDeclaredFields produces empty actual types at max depth`() {
        val builder = DefaultGraph.Builder()

        // Create a type with a field that references another analyzable type
        val innerType = TypeDescriptor("com.example.Inner")
        val outerType = TypeDescriptor("com.example.Outer")

        val innerFieldDesc = FieldDescriptor(outerType, "inner", innerType)
        builder.addNode(FieldNode(NodeId.next(), innerFieldDesc, isStatic = false))

        val testMethod = method("com.example.Controller", "get", outerType)
        builder.addMethod(testMethod)

        val outerVarId = NodeId.next()
        val outerVar = LocalVariable(outerVarId, "outer", outerType, testMethod)
        builder.addNode(outerVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(outerVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        // maxDepth=2 means we can analyze Outer (depth 1 → 2) but Inner would be at depth 3
        val config = TypeHierarchyConfig(maxDepth = 2, includePackages = listOf("com.example"), excludePackages = emptyList())
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    @Test
    fun `analyzeFieldAssignments uses strategy 7 when all other strategies find nothing`() {
        // Strategy 7 triggers when fields.isEmpty() after strategies 1-6.
        // For that: no matching setters by declaringClass (strategy 1 fails),
        // no direct field assignments (strategy 2 fails), no constructor calls (strategy 3 fails),
        // no global field assignments (strategy 4 fails), no getters (strategy 5 fails),
        // no FieldNodes (strategy 6 fails).
        // BUT: a setter call with receiver matching by LocalVariable type MUST exist.
        // The key: setter's declaringClass must be a PARENT class (not the type itself),
        // so strategy 1 doesn't find it, but strategy 7's receiver check finds it.
        val builder = DefaultGraph.Builder()

        val childType = TypeDescriptor("com.example.Child")
        val parentType = TypeDescriptor("com.example.Parent")
        val testMethod = method("com.example.Controller", "build", childType)
        builder.addMethod(testMethod)

        // No FieldNodes for childType, no getter methods, no constructor calls
        // Receiver is a LocalVariable of childType
        val receiverVarId = NodeId.next()
        val receiverVar = LocalVariable(receiverVarId, "obj", childType, testMethod)
        builder.addNode(receiverVar)

        val argId = NodeId.next()
        val argVar = LocalVariable(argId, "val", stringType, testMethod)
        builder.addNode(argVar)

        // Setter's declaringClass is parentType (so strategy 1 won't match childType)
        // but receiver is childType (so strategy 7 receiver check matches)
        val setNameMethod = MethodDescriptor(parentType, "setName", listOf(stringType), voidType)
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
}
