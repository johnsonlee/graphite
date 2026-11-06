package io.johnsonlee.graphite.analysis

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
import kotlin.test.Test
import kotlin.test.assertTrue

internal class TypeHierarchyFieldDiscoveryTest : TypeHierarchyAnalysisTestSupport() {
    @Test
    fun `analysis discovers fields from getter methods`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Add getter methods for User
        val getNameMethod = MethodDescriptor(userType, "getName", emptyList(), stringType)
        val getAgeMethod = MethodDescriptor(userType, "getAge", emptyList(), intType)
        builder.addMethod(getNameMethod)
        builder.addMethod(getAgeMethod)

        // Return node with user
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
    fun `analysis discovers declared fields from FieldNodes`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // FieldNode for User.email
        val emailFieldDesc = FieldDescriptor(userType, "email", stringType)
        val emailFieldId = NodeId.next()
        val emailField = FieldNode(emailFieldId, emailFieldDesc, isStatic = false)
        builder.addNode(emailField)

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
    fun `analysis discovers boolean fields from isXxx getters`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Boolean getter: isActive()
        val isActiveMethod = MethodDescriptor(userType, "isActive", emptyList(), TypeDescriptor("boolean"))
        builder.addMethod(isActiveMethod)

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
    fun `analysis discovers declared fields including primitives and collections`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Fields of various types
        val nameFieldDesc = FieldDescriptor(userType, "name", stringType)
        builder.addNode(FieldNode(NodeId.next(), nameFieldDesc, isStatic = false))

        val ageFieldDesc = FieldDescriptor(userType, "age", intType)
        builder.addNode(FieldNode(NodeId.next(), ageFieldDesc, isStatic = false))

        val listType = TypeDescriptor("java.util.List")
        val itemsFieldDesc = FieldDescriptor(userType, "items", listType)
        builder.addNode(FieldNode(NodeId.next(), itemsFieldDesc, isStatic = false))

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
        // Should discover string (primitive), int (primitive), list (collection)
        assertTrue(userStructure.fields.containsKey("name"))
        assertTrue(userStructure.fields.containsKey("age"))
        assertTrue(userStructure.fields.containsKey("items"))
    }

    @Test
    fun `analysis collects all supertypes for field discovery`() {
        val builder = DefaultGraph.Builder()

        val parentType = TypeDescriptor("com.example.BaseEntity")
        val grandParentType = TypeDescriptor("com.example.AbstractEntity")

        // User extends BaseEntity extends AbstractEntity
        builder.addTypeRelation(userType, parentType, TypeRelation.EXTENDS)
        builder.addTypeRelation(parentType, grandParentType, TypeRelation.EXTENDS)

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // GrandParent has a getter
        val getIdMethod = MethodDescriptor(grandParentType, "getId", emptyList(), TypeDescriptor("long"))
        builder.addMethod(getIdMethod)

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
    fun `analysis discovers boolean fields from isXxx getters returning java lang Boolean`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Boolean getter: isEnabled() returning java.lang.Boolean
        val isEnabledMethod = MethodDescriptor(userType, "isEnabled", emptyList(), TypeDescriptor("java.lang.Boolean"))
        builder.addMethod(isEnabledMethod)

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
    fun `getterBasedFields builds type structure for analyzable return types`() {
        val builder = DefaultGraph.Builder()

        val containerType = TypeDescriptor("com.example.Container")
        val testMethod = method("com.example.Controller", "get", containerType)
        builder.addMethod(testMethod)

        // Register a getter method for Container: getUser() returns User
        val getUserMethod = MethodDescriptor(containerType, "getUser", emptyList(), userType)
        builder.addMethod(getUserMethod)

        val containerVarId = NodeId.next()
        val containerVar = LocalVariable(containerVarId, "c", containerType, testMethod)
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
        val container = result.returnStructures.first()
        // Should have discovered "user" field from getUser() getter
        assertTrue(container.fields.containsKey("user"))
    }
}
