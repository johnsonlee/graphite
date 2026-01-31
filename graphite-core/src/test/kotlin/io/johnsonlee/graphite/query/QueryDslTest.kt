package io.johnsonlee.graphite.query

import io.johnsonlee.graphite.analysis.DataFlowPath
import io.johnsonlee.graphite.analysis.PropagationNodeType
import io.johnsonlee.graphite.analysis.PropagationPath
import io.johnsonlee.graphite.analysis.PropagationSourceType
import io.johnsonlee.graphite.analysis.PropagationStep
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryDslTest {

    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    private fun makeMethod(className: String = "com.example.Test", name: String = "test"): MethodDescriptor {
        return MethodDescriptor(TypeDescriptor(className), name, emptyList(), TypeDescriptor("void"))
    }

    // ========================================================================
    // findArgumentConstants
    // ========================================================================

    @Test
    fun `findArgumentConstants finds constant passed to matching call site`() {
        val callerMethod = makeMethod(name = "main")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            listOf(TypeDescriptor("java.lang.Integer")), TypeDescriptor("boolean")
        )

        val constId = NodeId.next()
        val constant = IntConstant(constId, 1001)

        val csId = NodeId.next()
        val callSite = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(constId))

        val graph = DefaultGraph.Builder()
            .addNode(constant)
            .addNode(callSite)
            .build()

        val query = GraphiteQuery(graph)
        val results = query.findArgumentConstants {
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
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "setConfig",
            listOf(TypeDescriptor("int"), TypeDescriptor("java.lang.String")),
            TypeDescriptor("void")
        )

        val arg0Id = NodeId.next()
        val arg1Id = NodeId.next()
        val arg0 = IntConstant(arg0Id, 42)
        val arg1 = StringConstant(arg1Id, "hello")

        val csId = NodeId.next()
        val callSite = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(arg0Id, arg1Id))

        val graph = DefaultGraph.Builder()
            .addNode(arg0)
            .addNode(arg1)
            .addNode(callSite)
            .build()

        val query = GraphiteQuery(graph)
        val results = query.findArgumentConstants {
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
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            listOf(TypeDescriptor("int")),
            TypeDescriptor("boolean")
        )

        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 999)
        val variable = LocalVariable(varId, "optionId", TypeDescriptor("int"), callerMethod)

        val csId = NodeId.next()
        val callSite = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(varId))

        val edge = DataFlowEdge(constId, varId, DataFlowKind.ASSIGN)

        val graph = DefaultGraph.Builder()
            .addNode(constant)
            .addNode(variable)
            .addNode(callSite)
            .addEdge(edge)
            .build()

        val query = GraphiteQuery(graph)
        val results = query.findArgumentConstants {
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
        val results = query.findArgumentConstants {
            method {
                declaringClass = "com.example.Missing"
                name = "missing"
            }
        }
        assertTrue(results.isEmpty())
    }

    // ========================================================================
    // findFieldsOfType
    // ========================================================================

    @Test
    fun `findFieldsOfType finds matching fields`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.User"), "name", TypeDescriptor("java.lang.String"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)

        val graph = DefaultGraph.Builder().addNode(field).build()
        val query = GraphiteQuery(graph)
        val results = query.findFieldsOfType {
            typePatterns = listOf("java.lang.String")
        }

        assertEquals(1, results.size)
        assertEquals("name", results[0].field.name)
    }

    @Test
    fun `findFieldsOfType with wildcard pattern`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.User"), "name", TypeDescriptor("java.lang.String"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)

        val graph = DefaultGraph.Builder().addNode(field).build()
        val query = GraphiteQuery(graph)
        val results = query.findFieldsOfType {
            typePatterns = listOf("java.lang.*")
        }

        assertEquals(1, results.size)
    }

    @Test
    fun `findFieldsOfType with compliance check`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.User"), "name", TypeDescriptor("java.lang.String"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)

        val graph = DefaultGraph.Builder().addNode(field).build()
        val query = GraphiteQuery(graph)
        val results = query.findFieldsOfType {
            typePatterns = listOf("java.lang.String")
            complianceCheck = { type -> type.className != "java.lang.String" }
        }

        assertEquals(1, results.size)
        assertFalse(results[0].isCompliant)
    }

    // ========================================================================
    // ArgumentConstantQuery
    // ========================================================================

    @Test
    fun `resolveIndices returns argumentIndices when set`() {
        val query = ArgumentConstantQuery().apply {
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

    // ========================================================================
    // MethodPatternBuilder
    // ========================================================================

    @Test
    fun `MethodPatternBuilder builds pattern`() {
        val builder = MethodPatternBuilder().apply {
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

    // ========================================================================
    // ArgumentConstantResult
    // ========================================================================

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

    // ========================================================================
    // ReturnTypeResult
    // ========================================================================

    @Test
    fun `ReturnTypeResult hasGenericReturn true for Object`() {
        val m = makeMethod()
        val result = ReturnTypeResult(
            method = MethodDescriptor(TypeDescriptor("Foo"), "get", emptyList(), TypeDescriptor("java.lang.Object")),
            declaredType = TypeDescriptor("java.lang.Object"),
            actualTypes = listOf(TypeDescriptor("com.example.User"))
        )
        assertTrue(result.hasGenericReturn)
    }

    @Test
    fun `ReturnTypeResult typesMismatch`() {
        val result = ReturnTypeResult(
            method = MethodDescriptor(TypeDescriptor("Foo"), "get", emptyList(), TypeDescriptor("java.lang.Object")),
            declaredType = TypeDescriptor("java.lang.Object"),
            actualTypes = listOf(TypeDescriptor("com.example.User"))
        )
        assertTrue(result.typesMismatch)
    }

    // ========================================================================
    // FieldTypeResult
    // ========================================================================

    @Test
    fun `FieldTypeResult construction`() {
        val fd = FieldDescriptor(TypeDescriptor("Foo"), "f", TypeDescriptor("int"))
        val result = FieldTypeResult(fd, TypeDescriptor("Foo"), isCompliant = true)
        assertTrue(result.isCompliant)
        assertEquals("f", result.field.name)
    }

    // ========================================================================
    // findActualReturnTypes
    // ========================================================================

    @Test
    fun `findActualReturnTypes finds actual type from local variable`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Controller"), "getUser", emptyList(),
            TypeDescriptor("java.lang.Object")
        )

        val userType = TypeDescriptor("com.example.User")
        val varId = NodeId.next()
        val variable = LocalVariable(varId, "user", userType, method)

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, method)

        val edge = DataFlowEdge(varId, returnId, DataFlowKind.ASSIGN)

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(variable)
        builder.addNode(returnNode)
        builder.addEdge(edge)
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results = query.findActualReturnTypes {
            method {
                declaringClass = "com.example.Controller"
                name = "getUser"
            }
        }

        assertEquals(1, results.size)
        assertTrue(results[0].actualTypes.any { it.className == "com.example.User" })
    }

    @Test
    fun `findActualReturnTypes traces through Object-typed local`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Controller"), "getData", emptyList(),
            TypeDescriptor("java.lang.Object")
        )

        val userType = TypeDescriptor("com.example.User")
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, method)

        val objVarId = NodeId.next()
        val objVar = LocalVariable(objVarId, "obj", TypeDescriptor("java.lang.Object"), method)

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, method)

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(userVar)
        builder.addNode(objVar)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, objVarId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(objVarId, returnId, DataFlowKind.ASSIGN))
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results = query.findActualReturnTypes {
            method {
                declaringClass = "com.example.Controller"
                name = "getData"
            }
        }

        assertEquals(1, results.size)
        assertTrue(results[0].actualTypes.any { it.className == "com.example.User" })
    }

    @Test
    fun `findActualReturnTypes resolves field node type`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Controller"), "getField", emptyList(),
            TypeDescriptor("java.lang.Object")
        )

        val fd = FieldDescriptor(TypeDescriptor("com.example.Holder"), "value", TypeDescriptor("com.example.Data"))
        val fieldId = NodeId.next()
        val field = FieldNode(fieldId, fd, isStatic = false)

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, method)

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(field)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(fieldId, returnId, DataFlowKind.FIELD_LOAD))
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results = query.findActualReturnTypes {
            method {
                declaringClass = "com.example.Controller"
                name = "getField"
            }
        }

        assertEquals(1, results.size)
        assertTrue(results[0].actualTypes.any { it.className == "com.example.Data" })
    }

    @Test
    fun `findActualReturnTypes resolves call site with concrete return type`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Controller"), "getUser", emptyList(),
            TypeDescriptor("java.lang.Object")
        )

        val callee = MethodDescriptor(
            TypeDescriptor("com.example.Repo"), "findUser", emptyList(),
            TypeDescriptor("com.example.User")
        )

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, method, callee, 10, null, emptyList())

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, method)

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(cs)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(csId, returnId, DataFlowKind.RETURN_VALUE))
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results = query.findActualReturnTypes {
            method {
                declaringClass = "com.example.Controller"
                name = "getUser"
            }
        }

        assertEquals(1, results.size)
        assertTrue(results[0].actualTypes.any { it.className == "com.example.User" })
    }

    @Test
    fun `findActualReturnTypes traces into callee for Object return type`() {
        // Inner method: returns User
        val innerMethod = MethodDescriptor(
            TypeDescriptor("com.example.Repo"), "findData", emptyList(),
            TypeDescriptor("java.lang.Object")
        )
        val userType = TypeDescriptor("com.example.User")
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, innerMethod)
        val innerReturnId = NodeId.next()
        val innerReturn = ReturnNode(innerReturnId, innerMethod)

        // Outer method: calls inner and returns result
        val outerMethod = MethodDescriptor(
            TypeDescriptor("com.example.Controller"), "getData", emptyList(),
            TypeDescriptor("java.lang.Object")
        )
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, outerMethod, innerMethod, 10, null, emptyList())
        val outerReturnId = NodeId.next()
        val outerReturn = ReturnNode(outerReturnId, outerMethod)

        val builder = DefaultGraph.Builder()
        builder.addMethod(innerMethod)
        builder.addMethod(outerMethod)
        builder.addNode(userVar)
        builder.addNode(innerReturn)
        builder.addNode(cs)
        builder.addNode(outerReturn)
        builder.addEdge(DataFlowEdge(userVarId, innerReturnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(csId, outerReturnId, DataFlowKind.RETURN_VALUE))
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results = query.findActualReturnTypes {
            method {
                declaringClass = "com.example.Controller"
                name = "getData"
            }
        }

        assertEquals(1, results.size)
        assertTrue(results[0].actualTypes.any { it.className == "com.example.User" })
    }

    @Test
    fun `findActualReturnTypes resolves constant node types`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Controller"), "getVal", emptyList(),
            TypeDescriptor("java.lang.Object")
        )

        val intId = NodeId.next()
        val intConst = IntConstant(intId, 42)
        val longId = NodeId.next()
        val longConst = LongConstant(longId, 100L)
        val floatId = NodeId.next()
        val floatConst = FloatConstant(floatId, 1.5f)
        val doubleId = NodeId.next()
        val doubleConst = DoubleConstant(doubleId, 2.5)
        val boolId = NodeId.next()
        val boolConst = BooleanConstant(boolId, true)
        val strId = NodeId.next()
        val strConst = StringConstant(strId, "hello")
        val enumId = NodeId.next()
        val enumConst = EnumConstant(enumId, TypeDescriptor("com.example.Status"), "ACTIVE")
        val nullId = NodeId.next()
        val nullConst = NullConstant(nullId)

        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, method)

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(intConst)
        builder.addNode(longConst)
        builder.addNode(floatConst)
        builder.addNode(doubleConst)
        builder.addNode(boolConst)
        builder.addNode(strConst)
        builder.addNode(enumConst)
        builder.addNode(nullConst)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(intId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(longId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(floatId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(doubleId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(boolId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(strId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(enumId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(nullId, returnId, DataFlowKind.ASSIGN))
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results = query.findActualReturnTypes {
            method {
                declaringClass = "com.example.Controller"
                name = "getVal"
            }
        }

        assertEquals(1, results.size)
        val types = results[0].actualTypes.map { it.className }.toSet()
        assertTrue(types.contains("java.lang.Integer"))
        assertTrue(types.contains("java.lang.Long"))
        assertTrue(types.contains("java.lang.Float"))
        assertTrue(types.contains("java.lang.Double"))
        assertTrue(types.contains("java.lang.Boolean"))
        assertTrue(types.contains("java.lang.String"))
        assertTrue(types.contains("com.example.Status"))
        // NullConstant doesn't contribute a type
    }

    @Test
    fun `findActualReturnTypes returns empty for no matching methods`() {
        val graph = DefaultGraph.Builder().build()
        val query = GraphiteQuery(graph)
        val results = query.findActualReturnTypes {
            method {
                declaringClass = "com.example.Missing"
                name = "missing"
            }
        }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findActualReturnTypes skips method with no actual types found`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Controller"), "empty", emptyList(),
            TypeDescriptor("void")
        )
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, method)

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(returnNode)
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results = query.findActualReturnTypes {
            method {
                declaringClass = "com.example.Controller"
                name = "empty"
            }
        }

        assertTrue(results.isEmpty())
    }

    // ========================================================================
    // findTypeHierarchy
    // ========================================================================

    @Test
    fun `findTypeHierarchy delegates to TypeHierarchyAnalysis`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Controller"), "getUser", emptyList(),
            TypeDescriptor("com.example.User")
        )
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", TypeDescriptor("com.example.User"), method)
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, method)

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(userVar)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(userVarId, returnId, DataFlowKind.ASSIGN))
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results = query.findTypeHierarchy {
            method {
                declaringClass = "com.example.Controller"
                name = "getUser"
            }
            config {
                copy(includePackages = listOf("com.example"), excludePackages = emptyList())
            }
        }

        assertEquals(1, results.size)
        assertEquals("getUser", results[0].method.name)
    }

    // ========================================================================
    // ReturnTypeResult additional tests
    // ========================================================================

    @Test
    fun `ReturnTypeResult hasGenericReturn true for wildcard type arg`() {
        val result = ReturnTypeResult(
            method = MethodDescriptor(
                TypeDescriptor("Foo"), "get", emptyList(),
                TypeDescriptor("java.util.List", listOf(TypeDescriptor("?")))
            ),
            declaredType = TypeDescriptor("java.util.List", listOf(TypeDescriptor("?"))),
            actualTypes = emptyList()
        )
        assertTrue(result.hasGenericReturn)
    }

    @Test
    fun `ReturnTypeResult hasGenericReturn false for concrete type`() {
        val result = ReturnTypeResult(
            method = MethodDescriptor(
                TypeDescriptor("Foo"), "get", emptyList(),
                TypeDescriptor("com.example.User")
            ),
            declaredType = TypeDescriptor("com.example.User"),
            actualTypes = listOf(TypeDescriptor("com.example.User"))
        )
        assertFalse(result.hasGenericReturn)
    }

    @Test
    fun `ReturnTypeResult typesMismatch false when types match`() {
        val td = TypeDescriptor("com.example.User")
        val result = ReturnTypeResult(
            method = MethodDescriptor(TypeDescriptor("Foo"), "get", emptyList(), td),
            declaredType = td,
            actualTypes = listOf(td)
        )
        assertFalse(result.typesMismatch)
    }

    @Test
    fun `ReturnTypeResult typesMismatch false when actual equals declared`() {
        val declared = TypeDescriptor("com.example.Base")
        val result = ReturnTypeResult(
            method = MethodDescriptor(TypeDescriptor("Foo"), "get", emptyList(), declared),
            declaredType = declared,
            actualTypes = listOf(declared)
        )
        assertFalse(result.typesMismatch)
    }

    // ========================================================================
    // TypeHierarchyQuery config DSL
    // ========================================================================

    @Test
    fun `TypeHierarchyQuery config DSL`() {
        val query = TypeHierarchyQuery()
        query.config {
            copy(maxDepth = 20, includePackages = listOf("com.example"))
        }
        assertEquals(20, query.config.maxDepth)
        assertEquals(listOf("com.example"), query.config.includePackages)
    }

    // ========================================================================
    // ArgumentConstantQuery config DSL
    // ========================================================================

    @Test
    fun `ArgumentConstantQuery config DSL`() {
        val query = ArgumentConstantQuery()
        query.config {
            copy(maxDepth = 30)
        }
        assertEquals(30, query.analysisConfig.maxDepth)
    }

    // ========================================================================
    // ReturnTypeQuery method DSL
    // ========================================================================

    @Test
    fun `ReturnTypeQuery method DSL`() {
        val query = ReturnTypeQuery()
        query.method {
            declaringClass = "com.example.Foo"
            name = "bar"
        }
        assertEquals("com.example.Foo", query.methodPattern.declaringClass)
        assertEquals("bar", query.methodPattern.name)
    }

    // ========================================================================
    // FieldTypeQuery
    // ========================================================================

    @Test
    fun `FieldTypeQuery defaults`() {
        val query = FieldTypeQuery()
        assertTrue(query.typePatterns.isEmpty())
    }

    // ========================================================================
    // findFieldsOfType no match
    // ========================================================================

    @Test
    fun `findFieldsOfType returns empty for no matching fields`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.User"), "age", TypeDescriptor("int"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)

        val graph = DefaultGraph.Builder().addNode(field).build()
        val query = GraphiteQuery(graph)
        val results = query.findFieldsOfType {
            typePatterns = listOf("java.lang.String")
        }

        assertTrue(results.isEmpty())
    }
}
