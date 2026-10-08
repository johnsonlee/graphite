package sootup.java.bytecode.frontend.conversion

import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.GraphiteContext
import io.johnsonlee.graphite.sootup.GraphiteExtension
import io.johnsonlee.graphite.sootup.ParsedClassLocation
import io.johnsonlee.graphite.sootup.SootUpAdapter
import java.nio.file.Files
import java.util.Optional
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import sootup.core.model.SootClass
import sootup.core.model.SourceType
import sootup.core.types.ClassType
import sootup.java.core.AnnotationUsage
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootClassSource
import sootup.java.core.OverridingJavaClassSource
import sootup.java.core.views.JavaView

class MethodSourceCacheLifetimeTest {
    @Test
    fun `pass two caches only its current class including unsupported nullable sources`() = withFixture { fixture ->
        lateinit var adapter: SootUpAdapter
        val visited = mutableListOf<String>()
        val extension = object : GraphiteExtension {
            override fun visit(sootClass: SootClass, context: GraphiteContext) {
                val current = sootClass as JavaSootClass
                visited += current.type.fullyQualifiedName
                assertSame(current, field(adapter, "methodCacheOwner"))
                for (method in listOf("streamingSources", "bytecodeMethods")) {
                    val first = lookup(adapter, method, current)
                    val second = lookup(adapter, method, current)
                    if (first != null) assertSame(first, second, "active positive result must be reused")
                    val cache = cache(adapter, method)
                    assertEquals(setOf<Any?>(current), cache.keys)
                    assertEquals(first, cache[current])
                    fixture.classes.filter { it !== current }.forEach { other ->
                        lookup(adapter, method, other)
                        assertEquals(setOf<Any?>(current), cache.keys, "other-class reads must not populate the active cache")
                    }
                }
                assertNotNull(invoke(adapter, "collectDeclaredMethodSubSignatures", String::class.java, "fixture.Base"))
                assertTrue(caches(adapter).all { it.keys == setOf(current) })
            }
        }
        adapter = fixture.adapter(extension)
        val graph = adapter.buildGraph()
        assertEquals(listOf("fixture.Base", "fixture.Child", "fixture.Unknown", "fixture.Control"), visited)
        val expected = setOf("fixture.Base.target", "fixture.Child.main", "fixture.Child.target",
            "fixture.Unknown.target", "fixture.Control.target")
        assertEquals(expected,
            graph.methods(MethodPattern()).map { "${it.declaringClass.className}.${it.name}" }.toSet())
        assertReleased(adapter)
    }

    @Test
    fun `discovery cleanup enum and control lookups never acquire cache ownership`() = withFixture { fixture ->
        val adapter = fixture.adapter()
        val entries = invoke(adapter, "findEntryPoints") as List<*>
        assertEquals(listOf("<fixture.Child: void main(java.lang.String[])>"), entries.map { it.toString() })
        assertReleased(adapter)
        val signatures = invoke(adapter, "collectDeclaredMethodSubSignatures", String::class.java, "fixture.Base")
        assertEquals(setOf("int target()"), signatures)
        assertReleased(adapter)
        for (sootClass in fixture.classes) {
            invoke(adapter, "releaseClassConversionState", SootClass::class.java, sootClass)
            lookup(adapter, "getAsmMethodNodes", sootClass)
            assertReleased(adapter)
        }
        val enumClass = fixture.classes.single { it.isEnum }
        invoke(adapter, "extractEnumValues", SootClass::class.java, enumClass)
        assertReleased(adapter)
        assertNotNull(invoke(adapter, "resolveBundleControlSpec", String::class.java, "fixture.Control"))
        assertReleased(adapter)
        assertNull(lookup(adapter, "streamingSources", fixture.classes[0]))
        assertNull(lookup(adapter, "bytecodeMethods", fixture.classes[2]))
        assertReleased(adapter)
    }

    @Test
    fun `extension failure releases both caches and preserves the original exception`() = withFixture { fixture ->
        val failure = IllegalArgumentException("extension failed after class methods")
        lateinit var adapter: SootUpAdapter
        val extension = object : GraphiteExtension {
            override fun visit(sootClass: SootClass, context: GraphiteContext) {
                val current = sootClass as JavaSootClass
                lookup(adapter, "streamingSources", current)
                assertNotNull(lookup(adapter, "bytecodeMethods", current))
                assertTrue(caches(adapter).all { it.keys == setOf(current) })
                throw failure
            }
        }
        adapter = fixture.adapter(extension)
        assertSame(failure, assertFailsWith<IllegalArgumentException> { adapter.buildGraph() })
        assertReleased(adapter)
        assertEquals(listOf("<fixture.Child: void main(java.lang.String[])>"),
            (invoke(adapter, "findEntryPoints") as List<*>).map { it.toString() })
        assertReleased(adapter)
    }

    private class Fixture(val classes: List<JavaSootClass>) {
        private val view = object : JavaView(emptyList()) {
            override fun getClasses(): Stream<JavaSootClass> = this@Fixture.classes.stream()
            override fun getClass(type: ClassType): Optional<JavaSootClass> =
                Optional.ofNullable(this@Fixture.classes.firstOrNull { it.type == type })
        }

        fun adapter(vararg extensions: GraphiteExtension) = SootUpAdapter(
            view, LoaderConfig(excludePackages = listOf("excluded"), trackCrossMethodFunctionalDispatch = false),
            extensions = extensions.toList(), inputLocationSources = emptyMap(), singleArtifactSource = "fixture.jar"
        )
    }

    private fun withFixture(action: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("method-cache-lifetime")
        val names = listOf("fixture.Base", "fixture.Child", "fixture.Unknown", "fixture.Control", "excluded.Choice")
        names.forEach { name ->
            val file = root.resolve(name.replace('.', '/') + ".class")
            Files.createDirectories(file.parent)
            Files.write(file, classBytes(name))
        }
        val location = ParsedClassLocation(root, SourceType.Application, emptyList())
        try {
            val view = JavaView(listOf(location))
            val classes = names.map { name -> view.getClass(view.identifierFactory.getClassType(name)).get() }.toMutableList()
            classes[0] = JavaSootClass(OverridingJavaClassSource(classes[0].classSource as JavaSootClassSource), SourceType.Application)
            classes[2] = JavaSootClass(OtherClassSource(classes[2].classSource as JavaSootClassSource), SourceType.Application)
            action(Fixture(classes))
        } finally {
            location.close()
            root.toFile().deleteRecursively()
        }
    }

    private class OtherClassSource(private val delegate: JavaSootClassSource) : JavaSootClassSource(delegate) {
        override fun resolveMethods() = delegate.resolveMethods()
        override fun resolveFields() = delegate.resolveFields()
        override fun resolveModifiers() = delegate.resolveModifiers()
        override fun resolveInterfaces() = delegate.resolveInterfaces()
        override fun resolveSuperclass() = delegate.resolveSuperclass()
        override fun resolveOuterClass() = delegate.resolveOuterClass()
        override fun resolvePosition() = delegate.resolvePosition()
        override fun resolveAnnotations(): Iterable<AnnotationUsage> = emptyList()
    }

    private fun classBytes(name: String): ByteArray {
        val enum = name == "excluded.Choice"
        val writer = ClassWriter(0)
        val parent = when (name) {
            "fixture.Child" -> "fixture/Base"
            "fixture.Control" -> "java/util/ResourceBundle\$Control"
            "excluded.Choice" -> "java/lang/Enum"
            else -> "java/lang/Object"
        }
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or (if (enum) Opcodes.ACC_ENUM else 0),
            name.replace('.', '/'), null, parent, null)
        val method = if (enum) "<clinit>" else "target"
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, method, if (enum) "()V" else "()I", null, null).apply {
            visitCode()
            if (!enum) visitInsn(Opcodes.ICONST_1)
            visitInsn(if (enum) Opcodes.RETURN else Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        if (name == "fixture.Child") {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 1)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun lookup(adapter: SootUpAdapter, name: String, sootClass: JavaSootClass): Any? =
        invoke(adapter, name, JavaSootClass::class.java, sootClass)

    private fun invoke(adapter: SootUpAdapter, name: String): Any? =
        SootUpAdapter::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(adapter)

    private fun invoke(adapter: SootUpAdapter, name: String, type: Class<*>, value: Any): Any? =
        SootUpAdapter::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(adapter, value)

    private fun field(adapter: SootUpAdapter, name: String): Any? =
        SootUpAdapter::class.java.getDeclaredField(name).apply { isAccessible = true }.get(adapter)

    private fun cache(adapter: SootUpAdapter, name: String): Map<*, *> = field(adapter, name + "Cache") as Map<*, *>

    private fun caches(adapter: SootUpAdapter): List<Map<*, *>> =
        listOf(cache(adapter, "streamingSources"), cache(adapter, "bytecodeMethods"))

    private fun assertReleased(adapter: SootUpAdapter) {
        assertNull(field(adapter, "methodCacheOwner"))
        assertTrue(caches(adapter).all { it.isEmpty() }, "method lists must not outlive the active pass-two class")
    }
}
