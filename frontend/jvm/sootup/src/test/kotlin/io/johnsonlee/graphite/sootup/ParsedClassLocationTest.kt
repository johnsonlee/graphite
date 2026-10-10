package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.bytecode.frontend.conversion.AsmAnnotationClassSource
import sootup.java.bytecode.frontend.conversion.parsedDeclarationNode
import sootup.java.core.views.JavaView

/**
 * [ParsedClassLocation] hands SootUp the classes `PathBasedAnalysisInputLocation` would, in the
 * same order, from a jar and from a class directory: `module-info.class` and a copy under
 * `META-INF/versions/` are skipped as SootUp skips them, a file that is no class is skipped, and
 * a class is found by its type.
 */
class ParsedClassLocationTest {

    private val root: Path = Files.createTempDirectory("parsed-class-location")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun classes(): Path {
        val sources = root.resolve("src/p/q").also { Files.createDirectories(it) }
        val files = mapOf(
            "A.java" to "package p.q; public class A { public java.util.List<String> names; public int plain; " +
                "public static int a() { return 1; } class Inner { } }",
            "B.java" to "package p.q; public class B extends A { public int b() { return a(); } }",
            "C.java" to "package p.q; public @interface C { " +
                "Class<? extends CharSequence> value() default String.class; }"
        ).map { (name, text) -> sources.resolve(name).also { Files.writeString(it, text) }.toString() }
        val classes = root.resolve("classes").also { Files.createDirectories(it) }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), *files.toTypedArray()))
        // What SootUp skips: a module descriptor and a multi-release copy that names another class.
        Files.write(classes.resolve("module-info.class"), classes.resolve("p/q/A.class").readBytes())
        Files.createDirectories(classes.resolve("META-INF/versions/9/p/q"))
        Files.write(classes.resolve("META-INF/versions/9/p/q/A.class"), classes.resolve("p/q/A.class").readBytes())
        return classes
    }

    private fun jar(classes: Path): Path {
        val jar = root.resolve("app.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classes).filter(Files::isRegularFile).sorted().forEach { file ->
                out.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                out.write(file.readBytes())
                out.closeEntry()
            }
        }
        return jar
    }

    private fun names(view: JavaView): List<String> = view.classes.map { it.type.fullyQualifiedName }.toList()

    @Test
    fun `a jar and a directory yield the classes SootUp yields, in SootUp's order`() {
        val classes = classes()
        for (input in listOf(jar(classes), classes)) {
            val parsed = ParsedClassLocation(input, SourceType.Application, emptyList())
            val theirs = PathBasedAnalysisInputLocation.create(input, SourceType.Application)
            val ours = names(JavaView(listOf(parsed)))
            assertEquals(names(JavaView(listOf(theirs))), ours, "$input")
            assertEquals(setOf("p.q.A", "p.q.A\$Inner", "p.q.B", "p.q.C"), ours.toSet(), "$input")
            val view = JavaView(listOf(parsed))
            val b = view.getClass(view.identifierFactory.getClassType("p.q.B")).get()
            assertEquals("p.q.A", b.superclass.get().fullyQualifiedName)
            val factory = view.identifierFactory
            assertTrue(b.getMethod(factory.getMethodSubSignature("b", factory.getType("int"), emptyList())).get().hasBody())
            assertTrue(view.getClass(view.identifierFactory.getClassType("p.q.C")).get().isAnnotation, "an annotation keeps its kind")
            assertTrue(view.getClass(view.identifierFactory.getClassType("p.q.Missing")).isEmpty, "no such class")
            assertTrue(view.getClass(view.identifierFactory.getClassType("META-INF.versions.9.p.q.A")).isEmpty, "names another class")
            assertEquals(SourceType.Application, parsed.sourceType)
            assertTrue(parsed.bodyInterceptors.isEmpty())
            parsed.close()
        }
    }

    @Test
    fun `the generic signatures of a parsed class's fields are kept from its one parse`() {
        val classes = classes()
        val location = ParsedClassLocation(classes, SourceType.Application, emptyList())
        JavaView(listOf(location)).classes.count()
        assertEquals(mapOf("names" to "Ljava/util/List<Ljava/lang/String;>;"), location.fieldSignatures("p.q.A"), "only the generic field")
        assertEquals(emptyMap(), location.fieldSignatures("p.q.B"), "parsed, no generic field")
        assertEquals(null, location.fieldSignatures("p.q.Missing"), "not parsed")
    }

    @Test
    fun `declaration snapshots remain reusable after parsed inputs are closed`() {
        val classes = classes()
        for (input in listOf(jar(classes), classes)) {
            val location = ParsedClassLocation(input, SourceType.Application, emptyList())
            val view = JavaView(listOf(location))
            val loaded = view.classes.toList()
            val annotation = loaded.single { it.type.fullyQualifiedName == "p.q.C" }
            assertIs<AsmAnnotationClassSource>(annotation.classSource)
            val graph = SootUpAdapter(
                view = view,
                config = LoaderConfig(buildCallGraph = false, includePackages = listOf("p.q")),
                extensions = emptyList(),
                inputLocationSources = mapOf(location to input.toString())
            ).buildGraph()
            val expected = graph.declaredTypes()
            val sources = loaded.associateBy { it.type.fullyQualifiedName }
            assertEquals(sources.keys, expected.classes.keys)
            val field = expected.fields.getValue(MemberTypeKey("p.q.A", "names", "Ljava/util/List;"))
            assertEquals("java.util.List<java.lang.String>", expected.render(field))
            val method = expected.methods.getValue(MemberTypeKey("p.q.C", "value", "()Ljava/lang/Class;"))
            assertEquals("java.lang.Class<? extends java.lang.CharSequence>", expected.render(method.returnType))

            location.close()
            if (Files.isDirectory(input)) {
                assertTrue(input.toFile().deleteRecursively())
            } else {
                Files.delete(input)
            }
            repeat(2) {
                // JavaView iteration order can change; match the table's order to compare local IDs.
                val declarations = expected.classes.keys.associateWith { name ->
                    ClassDeclarations.from(assertNotNull(sources.getValue(name).classSource.parsedDeclarationNode()))
                }
                val actual = DeclaredTypesReader(declarations).build()
                assertEquals(expected, actual, "complete declarations after closing $input, pass $it")
            }
        }
    }

    @Test
    fun `a file that is no class is skipped`() {
        val classes = classes()
        Files.createDirectories(classes.resolve("p/q/r"))
        Files.write(classes.resolve("p/q/r/Broken.class"), byteArrayOf(1, 2, 3))
        val view = JavaView(listOf(ParsedClassLocation(classes, SourceType.Application, emptyList())))
        assertEquals(setOf("p.q.A", "p.q.A\$Inner", "p.q.B", "p.q.C"), names(view).toSet())
        assertTrue(view.getClass(view.identifierFactory.getClassType("p.q.r.Broken")).isEmpty)
    }

    @Test
    fun `a missing field descriptor is rejected during the original parse`() {
        val classes = Files.createDirectories(root.resolve("missing-descriptor/p/q"))
        fun fieldClass(name: String): ByteArray = ClassWriter(0).apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "p/q/$name", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd()
            visitEnd()
        }.toByteArray()
        val broken = fieldClass("Broken")
        val reader = ClassReader(broken)
        val fieldCountOffset = reader.header + 8 + 2 * reader.readUnsignedShort(reader.header + 6)
        assertEquals(1, reader.readUnsignedShort(fieldCountOffset))
        // descriptor_index = 0 makes ASM expose a null descriptor.
        broken[fieldCountOffset + 6] = 0
        broken[fieldCountOffset + 7] = 0
        Files.write(classes.resolve("Broken.class"), broken)
        Files.write(classes.resolve("Complete.class"), fieldClass("Complete"))
        val location = ParsedClassLocation(root.resolve("missing-descriptor"), SourceType.Application, emptyList())
        try {
            val view = JavaView(listOf(location))
            assertEquals(listOf("p.q.Complete"), names(view))
            assertTrue(view.getClass(view.identifierFactory.getClassType("p.q.Broken")).isEmpty)
        } finally {
            location.close()
        }
    }

    @Test
    fun `a truncated class attribute count is rejected without padding the input`() {
        val classes = root.resolve("truncated-classes")
        val packageDir = Files.createDirectories(classes.resolve("p/q"))
        val truncated = tinyClassBytes("p/q/Truncated")
        assertTrue(truncated.size < 256, "exercise ASM's minimum stream buffer size")
        assertEquals(listOf(0.toByte(), 0.toByte()), truncated.takeLast(2), "the final field is class attributes_count")
        Files.write(packageDir.resolve("Truncated.class"), truncated.copyOf(truncated.size - 2))
        Files.write(packageDir.resolve("Complete.class"), tinyClassBytes("p/q/Complete"))

        for (input in listOf(jar(classes), classes)) {
            val location = ParsedClassLocation(input, SourceType.Application, emptyList())
            try {
                val view = JavaView(listOf(location))
                assertEquals(listOf("p.q.Complete"), names(view), "the truncated class must be skipped in $input")
                val factory = view.identifierFactory
                assertTrue(view.getClass(factory.getClassType("p.q.Truncated")).isEmpty, "$input")
                val complete = view.getClass(factory.getClassType("p.q.Complete")).get()
                val method = complete.getMethod(factory.getMethodSubSignature("value", factory.getType("int"), emptyList())).get()
                val returned = assertIs<JReturnStmt>(method.body.controlFlowGraph.stmts.single())
                assertEquals(7, assertIs<IntConstant>(returned.op).value, "the complete control class stays usable in $input")
            } finally {
                location.close()
            }
        }
    }

    private fun tinyClassBytes(name: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a graph built through the parsed location is the graph SootUp's location builds`() {
        val classes = classes()
        val graph = JavaProjectLoader(LoaderConfig(includePackages = listOf("p.q"))).load(jar(classes))
        val callees = graph.nodes(CallSiteNode::class.java)
            .map { "${it.caller.name}->${it.callee.name}" }.filter { "<init>" !in it }.toList()
        assertEquals(listOf("b->a"), callees)
    }
}
