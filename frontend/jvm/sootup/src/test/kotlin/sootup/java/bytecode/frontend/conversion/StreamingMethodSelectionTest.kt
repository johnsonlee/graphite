package sootup.java.bytecode.frontend.conversion

import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.ParsedClassLocation
import io.johnsonlee.graphite.sootup.SootUpAdapter
import java.nio.file.Files
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.model.SootClass
import sootup.core.model.SourceType
import sootup.core.signatures.MethodSignature
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootMethod
import sootup.java.core.views.JavaView

class StreamingMethodSelectionTest {
    @Test
    fun `entry selection skips unrelated wrappers and preserves matching annotation failures`() {
        withClasses(selectionClassBytes()) { optimized, _ ->
            val sources = assertNotNull(optimized.classSource.streamingMethodSources())
            var unrelatedReads = 0
            sources.filter { it.name != "main" || (it.access and Opcodes.ACC_STATIC) == 0 }.forEach {
                it.visibleAnnotations = unreadableAnnotations { unrelatedReads++ }
            }
            val broken = sources.single { it.name == "main" && it.desc == "(I)V" }
            broken.visibleAnnotations = arrayListOf(AnnotationNode("Lfixture/Broken;").apply {
                values = arrayListOf<Any>("missing-value")
            })
            val logs = mutableListOf<String>()
            val guarded = object : JavaSootClass(optimized.classSource, SourceType.Application) {
                override fun getMethods(): Set<JavaSootMethod> = error("persistent method cache was resolved")
            }
            val adapter = selectionAdapter(guarded, logs)
            val find = SootUpAdapter::class.java.getDeclaredMethod("findEntryPoints").apply { isAccessible = true }
            repeat(2) {
                seedScratch(sources)
                val signatures = assertIs<List<*>>(find.invoke(adapter)).map { assertIs<MethodSignature>(it) }
                assertEquals(listOf("<fixture.Streamed: void main()>", "<fixture.Streamed: void main(java.lang.String[])>"),
                    signatures.map { it.toString() })
                signatures.forEach { signature ->
                    val source = sources.single { it.signatureFor(optimized.type) == signature }
                    assertSame(source.getSignature(), signature)
                }
                assertEquals(0, unrelatedReads, "raw non-matches must not convert any annotation metadata")
                assertTrue(logs.any { it.contains("Skipping method fixture.Streamed.main(I)V:") })
                assertScratchReleased(sources)
                for (field in listOf("streamingSourcesCache", "bytecodeMethodsCache")) {
                    assertEquals(emptyMap<Any, Any>(), SootUpAdapter::class.java.getDeclaredField(field).apply {
                        isAccessible = true
                    }.get(adapter), "selection outside pass 2 must not retain classes")
                }
            }
        }
    }

    @Test
    fun `static initializer selection keeps full metadata and independent bodies with immediate cleanup`() {
        withClasses(selectionClassBytes()) { optimized, stock ->
            val sources = assertNotNull(optimized.classSource.streamingMethodSources())
            var unrelatedReads = 0
            sources.filter { it.name != "<clinit>" }.forEach {
                it.visibleAnnotations = unreadableAnnotations { unrelatedReads++ }
            }
            val expected = stock.methods.single { it.name == "<clinit>" }
            val source = sources.single { it.name == "<clinit>" }
            val guarded = object : JavaSootClass(optimized.classSource, SourceType.Application) {
                override fun getMethods(): Set<JavaSootMethod> = error("persistent method cache was resolved")
            }
            val adapter = selectionAdapter(guarded)
            val first = selectStaticMethod(adapter, guarded, "<clinit>")
            assertNotNull(first)
            seedScratch(sources)
            val second = assertNotNull(selectStaticMethod(adapter, guarded, "<clinit>"))
            assertNotSame(first, second)
            assertSame(first.signature, second.signature)
            assertSame(source.getSignature(), second.signature)
            assertEquals(expected.modifiers, second.modifiers)
            assertEquals(expected.exceptionSignatures, second.exceptionSignatures)
            assertEquals(expected.annotations.toList(), second.annotations.toList())
            val annotation = second.annotations.single()
            assertEquals("fixture.Initializer", annotation.annotation.fullyQualifiedName)
            assertEquals(setOf("value"), annotation.values.keys)
            assertEquals("kept", assertIs<SootStringConstant>(annotation.values.getValue("value")).value)
            assertScratchReleased(sources)
            assertEquals(0, unrelatedReads)
            val firstBody = first.body
            val secondBody = second.body
            assertNotSame(firstBody, secondBody)
            assertEquals(expected.body.controlFlowGraph.stmts.map { it.toString() },
                firstBody.controlFlowGraph.stmts.map { it.toString() })
            assertEquals(expected.body.controlFlowGraph.stmts.map { it.toString() },
                secondBody.controlFlowGraph.stmts.map { it.toString() })
            assertEquals(listOf("return"), secondBody.controlFlowGraph.stmts.map { it.toString() })
            releaseScratch(source)
        }
    }

    @Test
    fun `failed initializer conversion still uses the existing class resolution fallback`() {
        withClasses(selectionClassBytes()) { optimized, _ ->
            val sources = assertNotNull(optimized.classSource.streamingMethodSources())
            sources.single { it.name == "<clinit>" }.visibleAnnotations =
                arrayListOf(AnnotationNode("Lfixture/Broken;").apply { values = arrayListOf<Any>("missing-value") })
            var eagerReads = 0
            val guarded = object : JavaSootClass(optimized.classSource, SourceType.Application) {
                override fun getMethods(): Set<JavaSootMethod> {
                    eagerReads++
                    error("fallback resolution failed")
                }
            }
            val logs = mutableListOf<String>()
            val adapter = selectionAdapter(guarded, logs)
            seedScratch(sources)
            assertNull(selectStaticMethod(adapter, guarded, "<clinit>"))
            assertEquals(1, eagerReads)
            assertTrue(logs.any { it.contains("Skipping method fixture.Streamed.<clinit>()V:") })
            assertTrue(logs.any { it.contains("fallback resolution failed") })
            assertScratchReleased(sources)
        }
    }

    @Test
    fun `ordinary bytecode sources and missing static initializers retain fallback behavior`() {
        withClasses(selectionClassBytes()) { _, stock ->
            val adapter = selectionAdapter(stock)
            val find = SootUpAdapter::class.java.getDeclaredMethod("findEntryPoints").apply { isAccessible = true }
            val signatures = assertIs<List<*>>(find.invoke(adapter)).map { assertIs<MethodSignature>(it).toString() }
            assertEquals(setOf("<fixture.Streamed: void main()>", "<fixture.Streamed: void main(int)>",
                "<fixture.Streamed: void main(java.lang.String[])>"), signatures.toSet())
            val actual = assertNotNull(selectStaticMethod(adapter, stock, "<clinit>"))
            val expected = stock.methods.single { it.name == "<clinit>" }
            assertEquals(expected.signature, actual.signature)
            assertEquals(expected.annotations.toList(), actual.annotations.toList())
            assertEquals(expected.body.controlFlowGraph.stmts.map { it.toString() },
                actual.body.controlFlowGraph.stmts.map { it.toString() })
        }
        withClasses(selectionClassBytes(includeInitializer = false)) { optimized, stock ->
            var eagerReads = 0
            val guarded = object : JavaSootClass(optimized.classSource, SourceType.Application) {
                override fun getMethods(): Set<JavaSootMethod> {
                    eagerReads++
                    return stock.methods
                }
            }
            assertNull(selectStaticMethod(selectionAdapter(guarded), guarded, "<clinit>"))
            assertEquals(1, eagerReads, "no successful streamed match must retain the original fallback")
        }
    }

    private fun selectionAdapter(sootClass: JavaSootClass, logs: MutableList<String> = mutableListOf()): SootUpAdapter {
        val view = object : JavaView(emptyList()) {
            override fun getClasses(): Stream<JavaSootClass> = Stream.of(sootClass)
        }
        return SootUpAdapter(view, LoaderConfig(buildCallGraph = false, verbose = logs::add),
            extensions = emptyList(), inputLocationSources = emptyMap(), singleArtifactSource = "fixture.jar")
    }

    private fun selectStaticMethod(adapter: SootUpAdapter, sootClass: SootClass, name: String): JavaSootMethod? =
        SootUpAdapter::class.java.getDeclaredMethod("findStaticMethod", SootClass::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(adapter, sootClass, name) as JavaSootMethod?

    private fun unreadableAnnotations(onRead: () -> Unit): MutableList<AnnotationNode> =
        object : java.util.AbstractList<AnnotationNode>() {
            override val size: Int get() = 1
            override fun get(index: Int): AnnotationNode {
                onRead()
                error("unrelated annotation conversion")
            }
        }

    private fun seedScratch(sources: List<AsmMethodSource>) {
        sources.forEach { source ->
            for (field in listOf("insnIndexCache", "localVarTypeAnnotationIndex")) {
                AsmMethodSource::class.java.getDeclaredField(field).apply { isAccessible = true }
                    .set(source, hashMapOf<Any, Any>())
            }
        }
    }

    private fun assertScratchReleased(sources: List<AsmMethodSource>) {
        sources.forEach { source ->
            assertNull(scratch(source, "insnIndexCache"))
            assertNull(scratch(source, "localVarTypeAnnotationIndex"))
        }
    }

    private fun selectionClassBytes(includeInitializer: Boolean = true): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Streamed", null, "java/lang/Object", null)
        fun method(name: String, desc: String, isStatic: Boolean) {
            val access = (if (name == "<clinit>") 0 else Opcodes.ACC_PUBLIC) or
                (if (isStatic) Opcodes.ACC_STATIC else 0)
            val method = writer.visitMethod(access, name, desc, null, arrayOf("java/io/IOException"))
            if (name == "<clinit>") {
                method.visitAnnotation("Lfixture/Initializer;", true).apply {
                    visit("value", "kept")
                    visitEnd()
                }
            }
            method.visitCode()
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(0, 2)
            method.visitEnd()
        }
        if (includeInitializer) method("<clinit>", "()V", true)
        method("main", "()V", true)
        method("main", "(I)V", true)
        method("main", "([Ljava/lang/String;)V", true)
        method("main", "(Ljava/lang/String;)V", false)
        method("zUnrelated", "()V", true)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun scratch(source: AsmMethodSource, name: String): Any? =
        AsmMethodSource::class.java.getDeclaredField(name).apply { isAccessible = true }.get(source)

    private fun releaseScratch(source: AsmMethodSource) {
        val adapter = SootUpAdapter(JavaView(emptyList()), LoaderConfig(buildCallGraph = false),
            extensions = emptyList(), inputLocationSources = emptyMap(), singleArtifactSource = "fixture.jar")
        SootUpAdapter::class.java.getDeclaredMethod("releaseConversionState", Any::class.java)
            .apply { isAccessible = true }.invoke(adapter, source)
    }

    private fun withClasses(bytes: ByteArray, action: (JavaSootClass, JavaSootClass) -> Unit) {
        val root = Files.createTempDirectory("streaming-method-selection")
        val file = root.resolve("fixture/Streamed.class")
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
        val optimized = ParsedClassLocation(root, SourceType.Application, emptyList())
        val stock = PathBasedAnalysisInputLocation.create(root, SourceType.Application, emptyList())
        try {
            fun load(view: JavaView): JavaSootClass = view.getClass(view.identifierFactory.getClassType("fixture.Streamed")).get()
            val actual = load(JavaView(listOf(optimized)))
            assertIs<GraphiteAsmClassSource>(actual.classSource)
            action(actual, load(JavaView(listOf(stock))))
        } finally {
            optimized.close()
            stock.close()
            root.toFile().deleteRecursively()
        }
    }
}
