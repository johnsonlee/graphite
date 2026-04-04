package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphiteContextTest {

    private fun createContext(captured: MutableList<String>): GraphiteContext {
        return GraphiteContext(
            methodDescriptorFactory = { method ->
                MethodDescriptor(
                    declaringClass = TypeDescriptor("Test"),
                    name = method.name,
                    parameterTypes = emptyList(),
                    returnType = TypeDescriptor("void")
                )
            },
            logger = { captured.add(it) },
            resources = EmptyResourceAccessor
        )
    }

    @Test
    fun `log without args passes message directly`() {
        val messages = mutableListOf<String>()
        val ctx = createContext(messages)
        ctx.log("hello world")
        assertEquals(listOf("hello world"), messages)
    }

    @Test
    fun `log with one arg replaces placeholder`() {
        val messages = mutableListOf<String>()
        val ctx = createContext(messages)
        ctx.log("Found {0} endpoints", 5)
        assertEquals(listOf("Found 5 endpoints"), messages)
    }

    @Test
    fun `log with multiple args replaces in order`() {
        val messages = mutableListOf<String>()
        val ctx = createContext(messages)
        ctx.log("{0} endpoints in {1}", 3, "UserController")
        assertEquals(listOf("3 endpoints in UserController"), messages)
    }

    @Test
    fun `log with null arg`() {
        val messages = mutableListOf<String>()
        val ctx = createContext(messages)
        ctx.log("value is {0}", null)
        assertEquals(listOf("value is null"), messages)
    }

    @Test
    fun `log with no placeholders but args ignores args`() {
        val messages = mutableListOf<String>()
        val ctx = createContext(messages)
        ctx.log("no placeholders", "ignored")
        assertEquals(listOf("no placeholders"), messages)
    }

}
