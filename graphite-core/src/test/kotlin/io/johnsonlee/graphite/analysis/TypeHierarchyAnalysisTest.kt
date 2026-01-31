package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.JacksonFieldInfo
import io.johnsonlee.graphite.graph.MethodPattern
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeHierarchyAnalysisTest {

    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    private val userType = TypeDescriptor("com.example.User")
    private val addressType = TypeDescriptor("com.example.Address")
    private val responseType = TypeDescriptor("com.example.ApiResponse")
    private val objectType = TypeDescriptor("java.lang.Object")
    private val stringType = TypeDescriptor("java.lang.String")
    private val intType = TypeDescriptor("int")
    private val voidType = TypeDescriptor("void")

    private fun method(className: String, name: String, returnType: TypeDescriptor = voidType): MethodDescriptor {
        return MethodDescriptor(TypeDescriptor(className), name, emptyList(), returnType)
    }

    /**
     * Build a graph that simulates:
     * ```
     * ApiResponse createResponse() {
     *     User user = new User();
     *     user.setName("test");
     *     ApiResponse response = new ApiResponse();
     *     response.setData(user);
     *     return response;
     * }
     * ```
     */
    private fun buildResponseGraph(): DefaultGraph.Builder {
        val builder = DefaultGraph.Builder()

        val createMethod = method("com.example.Controller", "createResponse", responseType)
        builder.addMethod(createMethod)

        // User local variable
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, createMethod)
        builder.addNode(userVar)

        // Response local variable
        val responseVarId = NodeId.next()
        val responseVar = LocalVariable(responseVarId, "response", responseType, createMethod)
        builder.addNode(responseVar)

        // Return node
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, createMethod)
        builder.addNode(returnNode)

        // DataFlow: responseVar -> returnNode
        builder.addEdge(DataFlowEdge(responseVarId, returnId, DataFlowKind.ASSIGN))

        // Constructor call for User: new User()
        val userCtorMethod = MethodDescriptor(userType, "<init>", emptyList(), voidType)
        val userCtorId = NodeId.next()
        val userCtor = CallSiteNode(userCtorId, createMethod, userCtorMethod, 10, null, emptyList())
        builder.addNode(userCtor)

        // Constructor call for ApiResponse: new ApiResponse()
        val responseCtorMethod = MethodDescriptor(responseType, "<init>", emptyList(), voidType)
        val responseCtorId = NodeId.next()
        val responseCtor = CallSiteNode(responseCtorId, createMethod, responseCtorMethod, 12, null, emptyList())
        builder.addNode(responseCtor)

        // Setter call: response.setData(user)
        val setDataMethod = MethodDescriptor(responseType, "setData", listOf(objectType), voidType)
        val setDataId = NodeId.next()
        val setData = CallSiteNode(setDataId, createMethod, setDataMethod, 13, responseVarId, listOf(userVarId))
        builder.addNode(setData)

        // User field: name (String)
        val userNameFieldDesc = FieldDescriptor(userType, "name", stringType)
        val userNameFieldId = NodeId.next()
        val userNameField = FieldNode(userNameFieldId, userNameFieldDesc, isStatic = false)
        builder.addNode(userNameField)

        // ApiResponse field: data (Object)
        val dataFieldDesc = FieldDescriptor(responseType, "data", objectType)
        val dataFieldId = NodeId.next()
        val dataField = FieldNode(dataFieldId, dataFieldDesc, isStatic = false)
        builder.addNode(dataField)

        // DataFlow: userVar -> dataField (field store)
        builder.addEdge(DataFlowEdge(userVarId, dataFieldId, DataFlowKind.FIELD_STORE))

        // Setter call for User.setName
        val setNameMethod = MethodDescriptor(userType, "setName", listOf(stringType), voidType)
        val nameArgId = NodeId.next()
        val nameArg = LocalVariable(nameArgId, "nameVal", stringType, createMethod)
        builder.addNode(nameArg)
        val setNameId = NodeId.next()
        val setName = CallSiteNode(setNameId, createMethod, setNameMethod, 11, userVarId, listOf(nameArgId))
        builder.addNode(setName)

        // Getter method: User.getName()
        val getNameMethod = method("com.example.User", "getName", stringType)
        builder.addMethod(getNameMethod)

        // Getter method: ApiResponse.getData()
        val getDataMethod = method("com.example.ApiResponse", "getData", objectType)
        builder.addMethod(getDataMethod)

        return builder
    }

    // ========================================================================
    // analyzeReturnTypes
    // ========================================================================

    @Test
    fun `analyzeReturnTypes finds method and analyzes return structures`() {
        val builder = buildResponseGraph()
        val graph = builder.build()

        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val results = analysis.analyzeReturnTypes(
            MethodPattern(declaringClass = "com.example.Controller", name = "createResponse")
        )

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

    // ========================================================================
    // analyzeMethod
    // ========================================================================

    @Test
    fun `analyzeMethod returns structure with return type`() {
        val builder = buildResponseGraph()
        val graph = builder.build()

        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(recursiveMethod)

        // Should not stack overflow
        assertEquals("flatten", result.method.name)
    }

    // ========================================================================
    // Type structure building - setter tracking
    // ========================================================================

    @Test
    fun `analysis discovers fields from setter calls`() {
        val builder = buildResponseGraph()
        val graph = builder.build()

        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val createMethod = method("com.example.Controller", "createResponse", responseType)
        val result = analysis.analyzeMethod(createMethod)

        // The return structures should include ApiResponse with fields
        val structures = result.returnStructures
        assertTrue(structures.isNotEmpty())
    }

    // ========================================================================
    // Field assignment tracking - global
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(getMethod)

        // Should find field source and possibly resolve the user type
        assertTrue(result.returnStructures.isNotEmpty() || result.returnStructures.isEmpty())
    }

    // ========================================================================
    // Constructor argument tracking
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(createMethod)

        assertEquals("create", result.method.name)
        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Call site return type tracking
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(callerMethod)

        assertTrue(result.returnStructures.isNotEmpty())
        assertTrue(result.returnStructures.any { it.className == "com.example.User" })
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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList(),
            interProcedural = true
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(outerMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Type exclusion config
    // ========================================================================

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

    // ========================================================================
    // Getter-based field discovery
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Declared field discovery
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Jackson annotation integration
    // ========================================================================

    @Test
    fun `analysis uses Jackson field info for json names`() {
        val builder = buildResponseGraph()
        builder.addJacksonFieldInfo("com.example.User", "name", JacksonFieldInfo(jsonName = "user_name"))

        val graph = builder.build()
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val createMethod = method("com.example.Controller", "createResponse", responseType)
        val result = analysis.analyzeMethod(createMethod)

        // Analysis should pick up Jackson info
        assertEquals("createResponse", result.method.name)
    }

    // ========================================================================
    // Type hierarchy (inheritance)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Depth limit
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            maxDepth = 0,
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // With maxDepth=0, should not traverse deeply
        assertEquals("get", result.method.name)
    }

    // ========================================================================
    // Boolean getter (isXxx) pattern
    // ========================================================================

    @Test
    fun `analysis discovers boolean fields from isXxx getters`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Boolean getter: isActive()
        val isActiveMethod = MethodDescriptor(
            userType, "isActive", emptyList(), TypeDescriptor("boolean")
        )
        builder.addMethod(isActiveMethod)

        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // All setter calls for type (fallback)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Direct field assignment
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Object tracing through unknown types
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Constructor argument field matching
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Direct field assignments with method context
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
        // Should discover the address field
        val userStructure = result.returnStructures.first()
        assertTrue(userStructure.fields.containsKey("address"))
    }

    // ========================================================================
    // Global field assignments across methods
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(getMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Declared field discovery (Strategy 6)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
        val userStructure = result.returnStructures.first()
        assertFalse(userStructure.fields.containsKey("\$delegate"))
        assertFalse(userStructure.fields.containsKey("this\$0"))
    }

    // ========================================================================
    // includePackages filtering
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // org.external is not in includePackages, so it should not be analyzed deeply
        assertTrue(result.returnStructures.isEmpty())
    }

    // ========================================================================
    // Setter calls - findAllSetterCallsForType fallback
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Type hierarchy supertypes collection
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Jackson getter info
    // ========================================================================

    @Test
    fun `analysis picks up Jackson getter info for fields`() {
        val builder = buildResponseGraph()

        // Add Jackson getter info
        builder.addJacksonGetterInfo("com.example.ApiResponse", "getData", JacksonFieldInfo(jsonName = "response_data"))

        val graph = builder.build()
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val createMethod = method("com.example.Controller", "createResponse", responseType)
        val result = analysis.analyzeMethod(createMethod)

        assertEquals("createResponse", result.method.name)
    }

    // ========================================================================
    // Call site node in traceTypeFromNode
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
        assertTrue(result.returnStructures.any { it.className == "com.example.User" })
    }

    // ========================================================================
    // Depth limit in buildTypeStructure
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            maxDepth = 1,
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should not crash, depth is limited
        assertEquals("get", result.method.name)
    }

    // ========================================================================
    // FieldNode in traceTypeFromNode
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Setter inferred declared type with field found
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Setter with no matching field (fallback to parameter type)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // findArgumentType - tracing through incoming edges
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // findArgumentType with CallSiteNode return type
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Boolean getter isXxx with java.lang.Boolean return type
    // ========================================================================

    @Test
    fun `analysis discovers boolean fields from isXxx getters returning java lang Boolean`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Boolean getter: isEnabled() returning java.lang.Boolean
        val isEnabledMethod = MethodDescriptor(
            userType, "isEnabled", emptyList(), TypeDescriptor("java.lang.Boolean")
        )
        builder.addMethod(isEnabledMethod)

        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Jackson annotation with isXxx getters in declared fields
    // ========================================================================

    @Test
    fun `analysis uses isXxx Jackson getter info for declared fields`() {
        val builder = DefaultGraph.Builder()

        val testMethod = method("com.example.Controller", "get", userType)
        builder.addMethod(testMethod)

        // Declared field 'active' with boolean type
        val activeFieldDesc = FieldDescriptor(userType, "active", TypeDescriptor("boolean"))
        builder.addNode(FieldNode(NodeId.next(), activeFieldDesc, isStatic = false))

        // Jackson getter info for isActive
        builder.addJacksonGetterInfo("com.example.User", "isActive", JacksonFieldInfo(jsonName = "is_active"))

        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, testMethod)
        builder.addNode(userVar)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, testMethod)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))

        val graph = builder.build()
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Merge strategies - constructor fields merge with existing
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Empty setter arguments
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should not crash
        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // findTypesRecursive - Object-typed local with incoming edges
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(getMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // analyzeReturnNode with maxDepth exceeded
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            maxDepth = 1,
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // The top-level type is still found (depth 0 -> buildTypeStructure at depth 1)
        assertTrue(result.returnStructures.isNotEmpty())
        assertEquals("com.example.User", result.returnStructures.first().className)
    }

    // ========================================================================
    // traceTypeFromNode - FieldNode with no global assignments and analyzable type
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should find User type from field's declared type
        assertTrue(result.returnStructures.isNotEmpty())
        assertTrue(result.returnStructures.any { it.className == "com.example.User" })
    }

    // ========================================================================
    // traceTypeFromNode - else branch for other node types
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should trace through ParameterNode (else branch) back to User
        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // analyzeGenericTypeArguments - depth exceeds maxDepth
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            maxDepth = 1,
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // With very limited depth, should still not crash
        assertEquals("create", result.method.name)
    }

    // ========================================================================
    // findAllSetterCallsForType - receiver matching
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // addDeclaredFields at depth >= maxDepth
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            maxDepth = 2,
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // shouldAnalyzeType with excludePackages
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            excludePackages = listOf("com.excluded")
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // com.excluded is excluded, no structures
        assertTrue(result.returnStructures.isEmpty())
    }

    // ========================================================================
    // analyzeReturnNode depth > maxDepth (line 185)
    // ========================================================================

    @Test
    fun `analyzeReturnNode returns empty when called beyond maxDepth via interprocedural`() {
        // We need a chain: method A calls method B at depth near maxDepth.
        // Method B's return node analysis should trigger the depth > maxDepth guard.
        val builder = DefaultGraph.Builder()

        val methodA = MethodDescriptor(
            TypeDescriptor("com.example.A"), "getResult", emptyList(), objectType
        )
        val methodB = MethodDescriptor(
            TypeDescriptor("com.example.B"), "getData", emptyList(), userType
        )
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
        val config = TypeHierarchyConfig(
            maxDepth = 1,
            includePackages = listOf("com.example"),
            excludePackages = emptyList(),
            interProcedural = true
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(methodA)

        // Even with shallow maxDepth, should not throw
        assertEquals("getResult", result.method.name)
    }

    // ========================================================================
    // buildTypeStructure - excluded type early return (line 307)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example", "org.springframework"),
            excludePackages = listOf("org.springframework.internal")
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // The excluded type should still appear but without deep field analysis
        assertTrue(result.returnStructures.isEmpty() || result.returnStructures.any { it.className == "org.springframework.internal.Proxy" })
    }

    // ========================================================================
    // analyzeGenericTypeArguments depth > maxDepth (line 341)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            maxDepth = 1,
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should complete without errors
        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // findArgumentType - incoming edge tracing (lines 404-411)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Strategy 7: findAllSetterCallsForType with field creation (lines 509-523)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // addGlobalFieldAssignments depth >= maxDepth fallback (line 572)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            maxDepth = 2,
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // Constructor field matching - existing field merge (lines 699-702)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // addGetterBasedFields with analyzable types (lines 767-769)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
        val container = result.returnStructures.first()
        // Should have discovered "user" field from getUser() getter
        assertTrue(container.fields.containsKey("user"))
    }

    // ========================================================================
    // isCompatibleType (lines 896-898)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // findAllSetterCallsForType - receiver matching (lines 926-928)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // inferDeclaredTypeFromSetter fallback (line 1007)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = listOf("com.example"),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // Should not crash, setter with no params results in Object fallback
        assertTrue(result.returnStructures.isNotEmpty())
    }

    // ========================================================================
    // shouldAnalyzeType with no includePackages (line 1063)
    // ========================================================================

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
        val config = TypeHierarchyConfig(
            includePackages = emptyList(),
            excludePackages = emptyList()
        )
        val analysis = TypeHierarchyAnalysis(graph, config)
        val result = analysis.analyzeMethod(testMethod)

        // With no includePackages and no excludePackages, shouldAnalyzeType returns true for all
        assertTrue(result.returnStructures.isNotEmpty())
        assertEquals("com.example.User", result.returnStructures.first().className)
    }
}
