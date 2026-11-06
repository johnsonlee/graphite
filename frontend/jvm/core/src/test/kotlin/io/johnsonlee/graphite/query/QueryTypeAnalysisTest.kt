package io.johnsonlee.graphite.query

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class QueryTypeAnalysisTest : QueryDslTestSupport() {
    @Test
    fun `findFieldsOfType finds matching fields`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.User"), "name", TypeDescriptor("java.lang.String"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)

        val graph = DefaultGraph.Builder().addNode(field).build()
        val query = GraphiteQuery(graph)
        val results = query.findFieldsOfType { typePatterns = listOf("java.lang.String") }

        assertEquals(1, results.size)
        assertEquals("name", results[0].field.name)
    }

    @Test
    fun `findFieldsOfType with wildcard pattern`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.User"), "name", TypeDescriptor("java.lang.String"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)

        val graph = DefaultGraph.Builder().addNode(field).build()
        val query = GraphiteQuery(graph)
        val results = query.findFieldsOfType { typePatterns = listOf("java.lang.*") }

        assertEquals(1, results.size)
    }

    @Test
    fun `findFieldsOfType with compliance check`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.User"), "name", TypeDescriptor("java.lang.String"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)

        val graph = DefaultGraph.Builder().addNode(field).build()
        val query = GraphiteQuery(graph)
        val results =
            query.findFieldsOfType {
                typePatterns = listOf("java.lang.String")
                complianceCheck = { type -> type.className != "java.lang.String" }
            }

        assertEquals(1, results.size)
        assertFalse(results[0].isCompliant)
    }

    @Test
    fun `ReturnTypeResult hasGenericReturn true for Object`() {
        val result =
            ReturnTypeResult(
                method = MethodDescriptor(TypeDescriptor("Foo"), "get", emptyList(), TypeDescriptor("java.lang.Object")),
                declaredType = TypeDescriptor("java.lang.Object"),
                actualTypes = listOf(TypeDescriptor("com.example.User")))
        assertTrue(result.hasGenericReturn)
    }

    @Test
    fun `ReturnTypeResult typesMismatch`() {
        val result =
            ReturnTypeResult(
                method = MethodDescriptor(TypeDescriptor("Foo"), "get", emptyList(), TypeDescriptor("java.lang.Object")),
                declaredType = TypeDescriptor("java.lang.Object"),
                actualTypes = listOf(TypeDescriptor("com.example.User")))
        assertTrue(result.typesMismatch)
    }

    @Test
    fun `FieldTypeResult construction`() {
        val fd = FieldDescriptor(TypeDescriptor("Foo"), "f", TypeDescriptor("int"))
        val result = FieldTypeResult(fd, TypeDescriptor("Foo"), isCompliant = true)
        assertTrue(result.isCompliant)
        assertEquals("f", result.field.name)
    }

    @Test
    fun `findActualReturnTypes finds actual type from local variable`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.Controller"), "getUser", emptyList(), TypeDescriptor("java.lang.Object"))

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
        val results =
            query.findActualReturnTypes {
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
        val method = MethodDescriptor(TypeDescriptor("com.example.Controller"), "getData", emptyList(), TypeDescriptor("java.lang.Object"))

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
        val results =
            query.findActualReturnTypes {
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
        val method = MethodDescriptor(TypeDescriptor("com.example.Controller"), "getField", emptyList(), TypeDescriptor("java.lang.Object"))

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
        val results =
            query.findActualReturnTypes {
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
        val method = MethodDescriptor(TypeDescriptor("com.example.Controller"), "getUser", emptyList(), TypeDescriptor("java.lang.Object"))

        val callee = MethodDescriptor(TypeDescriptor("com.example.Repo"), "findUser", emptyList(), TypeDescriptor("com.example.User"))

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
        val results =
            query.findActualReturnTypes {
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
        val innerMethod = MethodDescriptor(TypeDescriptor("com.example.Repo"), "findData", emptyList(), TypeDescriptor("java.lang.Object"))
        val userType = TypeDescriptor("com.example.User")
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, innerMethod)
        val innerReturnId = NodeId.next()
        val innerReturn = ReturnNode(innerReturnId, innerMethod)

        // Outer method: calls inner and returns result
        val outerMethod =
            MethodDescriptor(TypeDescriptor("com.example.Controller"), "getData", emptyList(), TypeDescriptor("java.lang.Object"))
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
        val results =
            query.findActualReturnTypes {
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
        val method = MethodDescriptor(TypeDescriptor("com.example.Controller"), "getVal", emptyList(), TypeDescriptor("java.lang.Object"))

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
        val resourceIds =
            listOf(
                ResourceValueNode(NodeId.next(), "app.yml", "int", 7, "yaml"),
                ResourceValueNode(NodeId.next(), "app.yml", "long", 8L, "yaml"),
                ResourceValueNode(NodeId.next(), "app.yml", "float", 1.25f, "yaml"),
                ResourceValueNode(NodeId.next(), "app.yml", "double", 2.5, "yaml"),
                ResourceValueNode(NodeId.next(), "app.yml", "boolean", true, "yaml"),
                ResourceValueNode(NodeId.next(), "app.yml", "string", "enabled", "yaml"))

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
        resourceIds.forEach(builder::addNode)
        builder.addNode(returnNode)
        builder.addEdge(DataFlowEdge(intId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(longId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(floatId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(doubleId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(boolId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(strId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(enumId, returnId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(nullId, returnId, DataFlowKind.ASSIGN))
        resourceIds.forEach { builder.addEdge(DataFlowEdge(it.id, returnId, DataFlowKind.ASSIGN)) }
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results =
            query.findActualReturnTypes {
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
        val results =
            query.findActualReturnTypes {
                method {
                    declaringClass = "com.example.Missing"
                    name = "missing"
                }
            }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findActualReturnTypes skips method with no actual types found`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.Controller"), "empty", emptyList(), TypeDescriptor("void"))
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, method)

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(returnNode)
        val graph = builder.build()

        val query = GraphiteQuery(graph)
        val results =
            query.findActualReturnTypes {
                method {
                    declaringClass = "com.example.Controller"
                    name = "empty"
                }
            }

        assertTrue(results.isEmpty())
    }

    @Test
    fun `findTypeHierarchy delegates to TypeHierarchyAnalysis`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.Controller"), "getUser", emptyList(), TypeDescriptor("com.example.User"))
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
        val results =
            query.findTypeHierarchy {
                method {
                    declaringClass = "com.example.Controller"
                    name = "getUser"
                }
                config { copy(includePackages = listOf("com.example"), excludePackages = emptyList()) }
            }

        assertEquals(1, results.size)
        assertEquals("getUser", results[0].method.name)
    }

    @Test
    fun `ReturnTypeResult hasGenericReturn true for wildcard type arg`() {
        val result =
            ReturnTypeResult(
                method =
                    MethodDescriptor(
                        TypeDescriptor("Foo"), "get", emptyList(), TypeDescriptor("java.util.List", listOf(TypeDescriptor("?")))),
                declaredType = TypeDescriptor("java.util.List", listOf(TypeDescriptor("?"))),
                actualTypes = emptyList())
        assertTrue(result.hasGenericReturn)
    }

    @Test
    fun `ReturnTypeResult hasGenericReturn false for concrete type`() {
        val result =
            ReturnTypeResult(
                method = MethodDescriptor(TypeDescriptor("Foo"), "get", emptyList(), TypeDescriptor("com.example.User")),
                declaredType = TypeDescriptor("com.example.User"),
                actualTypes = listOf(TypeDescriptor("com.example.User")))
        assertFalse(result.hasGenericReturn)
    }

    @Test
    fun `ReturnTypeResult typesMismatch false when types match`() {
        val td = TypeDescriptor("com.example.User")
        val result =
            ReturnTypeResult(
                method = MethodDescriptor(TypeDescriptor("Foo"), "get", emptyList(), td), declaredType = td, actualTypes = listOf(td))
        assertFalse(result.typesMismatch)
    }
}
