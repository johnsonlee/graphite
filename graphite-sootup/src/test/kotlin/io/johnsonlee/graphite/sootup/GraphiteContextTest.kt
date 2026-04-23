package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.views.JavaView

class GraphiteContextTest {
    private val identifierFactory = JavaIdentifierFactory.getInstance()

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

    @Test
    fun `toMethodDescriptor delegates to factory`() {
        val expected = MethodDescriptor(
            declaringClass = TypeDescriptor("sample.resources.ResourceConfig"),
            name = "featureMode",
            parameterTypes = emptyList(),
            returnType = TypeDescriptor("java.lang.String")
        )
        val sootMethod = resolveTestMethod("sample.resources.ResourceConfig", "featureMode")
        val ctx = GraphiteContext(
            methodDescriptorFactory = { expected },
            logger = {},
            resources = EmptyResourceAccessor
        )

        assertSame(expected, ctx.toMethodDescriptor(sootMethod))
    }

    @Test
    fun `resources accessor is exposed`() {
        val ctx = createContext(mutableListOf())
        assertSame(EmptyResourceAccessor, ctx.resources)
    }

    private fun resolveTestMethod(className: String, methodName: String) =
        JavaView(
            listOf<AnalysisInputLocation>(
                PathBasedAnalysisInputLocation.create(findTestClassesDir(), SourceType.Application)
            )
        ).getClass(identifierFactory.getClassType(className))
            .orElseThrow()
            .methods
            .first { it.name == methodName }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        val path = if (submodulePath.exists()) submodulePath else rootPath
        assertTrue(path.exists(), "Test classes directory should exist: $path")
        return path
    }

}
