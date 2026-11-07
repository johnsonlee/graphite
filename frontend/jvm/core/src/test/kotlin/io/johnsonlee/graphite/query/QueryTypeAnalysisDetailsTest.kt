package io.johnsonlee.graphite.query

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class QueryTypeAnalysisDetailsTest : QueryDslTestSupport() {
    @Test
    fun `ReturnTypeResult typesMismatch false when actual equals declared`() {
        val declared = TypeDescriptor("com.example.Base")
        val result =
            ReturnTypeResult(
                method = MethodDescriptor(TypeDescriptor("Foo"), "get", emptyList(), declared),
                declaredType = declared,
                actualTypes = listOf(declared))
        assertFalse(result.typesMismatch)
    }

    @Test
    fun `TypeHierarchyQuery config DSL`() {
        val query = TypeHierarchyQuery()
        query.config { copy(maxDepth = 20, includePackages = listOf("com.example")) }
        assertEquals(20, query.config.maxDepth)
        assertEquals(listOf("com.example"), query.config.includePackages)
    }

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

    @Test
    fun `FieldTypeQuery defaults`() {
        val query = FieldTypeQuery()
        assertTrue(query.typePatterns.isEmpty())
    }

    @Test
    fun `findFieldsOfType returns empty for no matching fields`() {
        val fd = FieldDescriptor(TypeDescriptor("com.example.User"), "age", TypeDescriptor("int"))
        val field = FieldNode(NodeId.next(), fd, isStatic = false)

        val graph = DefaultGraph.Builder().addNode(field).build()
        val query = GraphiteQuery(graph)
        val results = query.findFieldsOfType { typePatterns = listOf("java.lang.String") }

        assertTrue(results.isEmpty())
    }
}
