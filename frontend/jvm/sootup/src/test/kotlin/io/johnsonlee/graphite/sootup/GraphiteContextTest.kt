package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            resources = EmptyResourceAccessor,
            annotations = { _, _, _, _ -> }
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
            resources = EmptyResourceAccessor,
            annotations = { _, _, _, _ -> }
        )

        assertSame(expected, ctx.toMethodDescriptor(sootMethod))
    }

    @Test
    fun `an annotation goes to the graph's writer, and one without a class, member or name is refused`() {
        val written = mutableListOf<List<Any?>>()
        val ctx = GraphiteContext(
            methodDescriptorFactory = { error("unused") },
            logger = {},
            resources = EmptyResourceAccessor,
            annotations = { className, memberName, annotation, values -> written += listOf(className, memberName, annotation, values) }
        )

        ctx.addMemberAnnotation("app.Routes", GraphiteContext.CLASS_MEMBER, "acme.Router", mapOf("prefix" to "/api"))
        ctx.addMemberAnnotation("app.Routes", "list", "acme.Route")

        val expected: List<List<Any?>> = listOf(
            listOf("app.Routes", "<class>", "acme.Router", mapOf("prefix" to "/api")),
            listOf("app.Routes", "list", "acme.Route", emptyMap<String, Any?>())
        )
        assertEquals(expected, written)
        for ((className, memberName, annotation) in listOf(Triple("", "m", "a.B"), Triple("app.C", "", "a.B"), Triple("app.C", "m", ""))) {
            val failure = assertFailsWith<IllegalArgumentException> {
                ctx.addMemberAnnotation(className, memberName, annotation)
            }
            assertTrue(failure.message!!.startsWith("An annotation needs a class, a member and a name"), failure.message)
        }
        assertEquals(2, written.size, "nothing was written for the refused ones")
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
        val rootPath = projectDir.resolve("frontend/jvm/sootup/build/classes/java/test")
        val path = if (submodulePath.exists()) submodulePath else rootPath
        assertTrue(path.exists(), "Test classes directory should exist: $path")
        return path
    }

}
