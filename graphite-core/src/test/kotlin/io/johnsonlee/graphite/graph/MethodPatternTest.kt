package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MethodPatternTest {

    private fun method(
        className: String = "com.example.Foo",
        name: String = "doWork",
        params: List<String> = emptyList(),
        returnType: String = "void"
    ): MethodDescriptor {
        return MethodDescriptor(
            TypeDescriptor(className),
            name,
            params.map { TypeDescriptor(it) },
            TypeDescriptor(returnType)
        )
    }

    // ========================================================================
    // Exact matching
    // ========================================================================

    @Test
    fun `matches with exact class and method`() {
        val pattern = MethodPattern(declaringClass = "com.example.Foo", name = "doWork")
        assertTrue(pattern.matches(method()))
    }

    @Test
    fun `does not match with wrong class`() {
        val pattern = MethodPattern(declaringClass = "com.example.Bar", name = "doWork")
        assertFalse(pattern.matches(method()))
    }

    @Test
    fun `does not match with wrong method name`() {
        val pattern = MethodPattern(declaringClass = "com.example.Foo", name = "other")
        assertFalse(pattern.matches(method()))
    }

    // ========================================================================
    // Wildcard matching
    // ========================================================================

    @Test
    fun `matches with wildcard class prefix`() {
        val pattern = MethodPattern(declaringClass = "com.example.*", name = "doWork")
        assertTrue(pattern.matches(method()))
    }

    @Test
    fun `matches with wildcard method prefix`() {
        val pattern = MethodPattern(name = "do*")
        assertTrue(pattern.matches(method()))
    }

    // ========================================================================
    // Regex matching
    // ========================================================================

    @Test
    fun `matches with regex class pattern`() {
        val pattern = MethodPattern(declaringClass = ".*Foo", name = "doWork", useRegex = true)
        assertTrue(pattern.matches(method()))
    }

    @Test
    fun `matches with regex method pattern`() {
        val pattern = MethodPattern(name = "do.*", useRegex = true)
        assertTrue(pattern.matches(method()))
    }

    @Test
    fun `does not match with non-matching regex`() {
        val pattern = MethodPattern(name = "^process.*", useRegex = true)
        assertFalse(pattern.matches(method()))
    }

    // ========================================================================
    // Parameter types filter
    // ========================================================================

    @Test
    fun `matches with correct parameter types`() {
        val pattern = MethodPattern(parameterTypes = listOf("int", "java.lang.String"))
        val m = method(params = listOf("int", "java.lang.String"))
        assertTrue(pattern.matches(m))
    }

    @Test
    fun `does not match with wrong param count`() {
        val pattern = MethodPattern(parameterTypes = listOf("int"))
        val m = method(params = listOf("int", "java.lang.String"))
        assertFalse(pattern.matches(m))
    }

    @Test
    fun `does not match with wrong param types`() {
        val pattern = MethodPattern(parameterTypes = listOf("long"))
        val m = method(params = listOf("int"))
        assertFalse(pattern.matches(m))
    }

    @Test
    fun `matches with null parameter types (any params)`() {
        val pattern = MethodPattern(parameterTypes = null)
        assertTrue(pattern.matches(method(params = listOf("int", "java.lang.String"))))
    }

    // ========================================================================
    // Return type filter
    // ========================================================================

    @Test
    fun `matches with correct return type`() {
        val pattern = MethodPattern(returnType = "java.lang.String")
        val m = method(returnType = "java.lang.String")
        assertTrue(pattern.matches(m))
    }

    @Test
    fun `does not match with wrong return type`() {
        val pattern = MethodPattern(returnType = "int")
        val m = method(returnType = "java.lang.String")
        assertFalse(pattern.matches(m))
    }

    // ========================================================================
    // Empty patterns match everything
    // ========================================================================

    @Test
    fun `empty pattern matches any method`() {
        val pattern = MethodPattern()
        assertTrue(pattern.matches(method()))
        assertTrue(pattern.matches(method(className = "com.other.Bar", name = "other")))
    }

    // ========================================================================
    // JacksonFieldInfo
    // ========================================================================

    @Test
    fun `JacksonFieldInfo default values`() {
        val info = JacksonFieldInfo()
        kotlin.test.assertNull(info.jsonName)
        assertFalse(info.isIgnored)
    }

    @Test
    fun `JacksonFieldInfo with values`() {
        val info = JacksonFieldInfo(jsonName = "user_name", isIgnored = true)
        kotlin.test.assertEquals("user_name", info.jsonName)
        assertTrue(info.isIgnored)
    }
}
