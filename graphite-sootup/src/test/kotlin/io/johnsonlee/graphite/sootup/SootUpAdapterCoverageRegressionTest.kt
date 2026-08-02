package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.objectweb.asm.tree.ClassNode
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.model.SourceType
import sootup.core.signatures.MethodSignature
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.jimple.basic.JavaLocal
import sootup.java.core.views.JavaView

class SootUpAdapterCoverageRegressionTest {
    private val identifierFactory = JavaIdentifierFactory.getInstance()

    @Test
    fun `small predicate helpers cover fallback branches`() {
        val adapter = createAdapter()
        val caller = MethodDescriptor(
            TypeDescriptor("sample.resources.ResourceConfig"),
            "lookup",
            emptyList(),
            TypeDescriptor("java.lang.String")
        )
        val local = JavaLocal("value", identifierFactory.getType("java.lang.String"), emptyList())
        val integerValueOf = identifierFactory.getMethodSignature(
            "java.lang.Integer",
            "valueOf",
            "java.lang.Integer",
            listOf("int")
        )

        assertTrue(
            invokePrivate<Boolean>(
                adapter,
                "isResourceRelevantCall",
                arrayOf(MethodSignature::class.java),
                identifierFactory.getMethodSignature("java.net.URL", "openStream", "java.io.InputStream", emptyList())
            )
        )
        assertTrue(
            invokePrivate<Boolean>(
                adapter,
                "isResourceRelevantCall",
                arrayOf(MethodSignature::class.java),
                identifierFactory.getMethodSignature(
                    "java.nio.channels.Channels",
                    "newReader",
                    "java.io.Reader",
                    listOf("java.nio.channels.ReadableByteChannel", "java.lang.String")
                )
            )
        )
        assertNull(invokePrivate<ComparisonOp?>(adapter, "comparisonOp", arrayOf(Any::class.java), Any()))
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractBoxedValue",
                arrayOf(JStaticInvokeExpr::class.java),
                JStaticInvokeExpr(integerValueOf, listOf(local))
            )
        )

        val parameterBindingClass = Class.forName("io.johnsonlee.graphite.sootup.SootUpAdapter\$ParameterBinding")
        val constructor = parameterBindingClass
            .getDeclaredConstructor(MethodDescriptor::class.java, Int::class.javaPrimitiveType)
            .also { it.isAccessible = true }
        val first = constructor.newInstance(caller, 0)
        val second = constructor.newInstance(caller, 0)
        val third = constructor.newInstance(caller, 1)
        assertTrue(first == second)
        assertFalse(first == third)
    }

    @Test
    fun `resource-backed class node loading covers fallback and misses`() {
        val adapter = createResourceBackedAdapter()

        val loaded = invokePrivate<ClassNode?>(
            adapter,
            "loadClassNodeFromResource",
            arrayOf(String::class.java),
            "sample.resources.MessagesListBundle_ko_KR"
        )
        assertEquals("sample/resources/MessagesListBundle_ko_KR", loaded?.name)
        assertNull(
            invokePrivate<ClassNode?>(
                adapter,
                "loadClassNodeFromResource",
                arrayOf(String::class.java),
                "sample.resources.MissingBundle"
            )
        )
    }

    @Test
    fun `resource artifact dependency extraction failure logs and continues`() {
        val logs = mutableListOf<String>()
        val testClassesDir = findTestClassesDir()
        val location = PathBasedAnalysisInputLocation.create(testClassesDir, SourceType.Application)
        val resourceAccessor = object : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> = emptySequence()
            override fun open(path: String) = error("cannot open $path")
        }
        val adapter = SootUpAdapter(
            view = JavaView(listOf(location)),
            config = LoaderConfig(verbose = logs::add),
            signatureReader = BytecodeSignatureReader(),
            extensions = emptyList(),
            resourceAccessor = resourceAccessor,
            inputLocationSources = mapOf(location to testClassesDir.fileName.toString()),
            graphBuilder = DefaultGraph.Builder()
        )

        invokePrivate<Unit>(
            adapter,
            "indexArtifactDependency",
            arrayOf(ResourceEntry::class.java),
            ResourceEntry("lib/Unreadable.class", "lib.jar")
        )

        assertTrue(logs.any { it.contains("Failed to extract artifact dependencies from lib.jar!/lib/Unreadable.class") })
    }

    private fun createAdapter(): SootUpAdapter {
        val testClassesDir = findTestClassesDir()
        val location = PathBasedAnalysisInputLocation.create(testClassesDir, SourceType.Application)
        val inputLocations: List<AnalysisInputLocation> = listOf(location)
        return SootUpAdapter(
            view = JavaView(inputLocations),
            config = LoaderConfig(includePackages = listOf("sample.resources"), buildCallGraph = false),
            signatureReader = BytecodeSignatureReader(),
            extensions = emptyList(),
            resourceAccessor = EmptyResourceAccessor,
            inputLocationSources = mapOf(location to testClassesDir.fileName.toString()),
            graphBuilder = DefaultGraph.Builder()
        )
    }

    private fun createResourceBackedAdapter(): SootUpAdapter {
        val testClassesDir = findTestClassesDir()
        val resources = mapOf(
            "sample/resources/MessagesListBundle_ko_KR.class" to findCompiledClassBytes(
                "sample/resources/MessagesListBundle_ko_KR.class"
            )
        )
        val location = PathBasedAnalysisInputLocation.create(testClassesDir, SourceType.Application)
        val inputLocations: List<AnalysisInputLocation> = listOf(location)
        val resourceAccessor = object : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> = emptySequence()
            override fun open(path: String) = resources[path]?.inputStream() ?: error("Missing resource $path")
        }
        return SootUpAdapter(
            view = JavaView(inputLocations),
            config = LoaderConfig(includePackages = listOf("sample.resources"), buildCallGraph = false),
            signatureReader = BytecodeSignatureReader(),
            extensions = emptyList(),
            resourceAccessor = resourceAccessor,
            inputLocationSources = mapOf(location to testClassesDir.fileName.toString()),
            graphBuilder = DefaultGraph.Builder()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(target: Any, name: String, parameterTypes: Array<Class<*>>, vararg args: Any?): T {
        val method = target.javaClass.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(target, *args) as T
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }

    private fun findCompiledClassBytes(relativePath: String): ByteArray =
        findTestClassesDir().resolve(relativePath).toFile().readBytes()
}
