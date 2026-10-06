package sootup.java.bytecode.frontend.conversion

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.ParsedClassLocation
import io.johnsonlee.graphite.sootup.SootUpAdapter
import java.nio.file.Files
import java.util.Optional
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypeReference
import sootup.core.jimple.basic.NoPositionInformation
import sootup.core.jimple.common.constant.EnumConstant
import sootup.core.jimple.common.constant.IntConstant as SootIntConstant
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.expr.JDivExpr
import sootup.core.jimple.common.ref.JCaughtExceptionRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIdentityStmt
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.core.model.SourceType
import sootup.core.signatures.MethodSubSignature
import sootup.core.types.ClassType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.AnnotationUsage
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootMethod
import sootup.java.core.jimple.basic.JavaLocal
import sootup.java.core.views.JavaView

class StreamingClassMethodsTest {

    @Test
    fun `streamed metadata includes declaration and return annotations in upstream order`() {
        withClasses { optimized, stock ->
            val sources = assertNotNull(optimized.classSource.streamingMethodSources())
            val expected = stock.methods.associateBy { it.signature.toString() }
            val actual = sources.map { source -> source.asStreamingMethod(optimized.type) }
            assertEquals(expected.keys, actual.map { it.signature.toString() }.toSet())
            actual.zip(sources).forEach { (method, source) ->
                val original = expected.getValue(method.signature.toString())
                assertEquals(original.modifiers, method.modifiers)
                assertEquals(original.exceptionSignatures, method.exceptionSignatures)
                assertEquals(original.annotations.toList(), method.annotations.toList())
                assertEquals(original.position, method.position)
                assertEquals(original.hasBody(), method.hasBody())
                assertSame(source, method.bodySource)
                assertSame(source.getSignature(), method.signature)
            }

            val choose = actual.single { it.name == "choose" && it.parameterCount == 2 }
            assertEquals("<fixture.Streamed: java.lang.String choose(int,java.lang.String[])>", choose.signature.toString())
            assertEquals(
                setOf(MethodModifier.PUBLIC, MethodModifier.STATIC, MethodModifier.FINAL, MethodModifier.VARARGS),
                choose.modifiers
            )
            assertEquals(listOf("java.io.IOException", "java.lang.ReflectiveOperationException"),
                choose.exceptionSignatures.map { it.fullyQualifiedName })
            assertSame(NoPositionInformation.getInstance(), choose.position)
            val annotations = choose.annotations.toList()
            assertEquals(listOf("fixture.Declared", "fixture.HiddenDeclared", "fixture.Returned", "fixture.HiddenReturned"),
                annotations.map { it.annotation.fullyQualifiedName })
            assertEquals(listOf("declaration", "hidden-declaration", "return", "hidden-return"),
                annotations.map { assertIs<SootStringConstant>(it.values.getValue("value")).value })
            assertEquals(7, assertIs<SootIntConstant>(annotations[2].values.getValue("rank")).value)

            val generic = actual.single { it.name == "generic" }
            assertEquals("<fixture.Streamed: java.lang.Object generic(java.util.List)>", generic.signature.toString())
            val abstractMethod = actual.single { it.name == "abstractValue" }
            assertEquals(setOf(MethodModifier.PUBLIC, MethodModifier.ABSTRACT), abstractMethod.modifiers)
            assertFalse(abstractMethod.hasBody())
            assertEquals(setOf(MethodModifier.PUBLIC), actual.single { it.name == "<init>" }.modifiers)
        }
    }

    @Test
    fun `rich annotation values remain equal to stock metadata across repeated streaming`() {
        withClasses { optimized, stock ->
            val source = assertNotNull(optimized.classSource.streamingMethodSources()).single { it.name == "generic" }
            val expected = stock.methods.single { it.name == "generic" }.annotations.toList()
            repeat(3) {
                val actual = source.asStreamingMethod(optimized.type).annotations.toList()
                assertEquals(expected, actual, "annotation metadata must survive repeated conversion of the same source")
                val annotation = actual.single()
                assertEquals("fixture.Rich", annotation.annotation.fullyQualifiedName)
                assertEquals(setOf("mode", "nested", "labels", "modes", "nestedArray"), annotation.values.keys)
                val mode = assertIs<EnumConstant>(annotation.values.getValue("mode"))
                assertEquals("fixture.Mode", assertIs<ClassType>(mode.type).fullyQualifiedName)
                assertEquals("FIRST", mode.value)
                val nested = assertIs<AnnotationUsage>(annotation.values.getValue("nested"))
                assertEquals("fixture.Nested", nested.annotation.fullyQualifiedName)
                assertEquals(setOf("value"), nested.values.keys)
                assertEquals("inside", assertIs<SootStringConstant>(nested.values.getValue("value")).value)
                val labels = assertIs<List<*>>(annotation.values.getValue("labels"))
                assertEquals(listOf("alpha", "beta"), labels.map { assertIs<SootStringConstant>(it).value })
                val modes = assertIs<List<*>>(annotation.values.getValue("modes")).map { assertIs<EnumConstant>(it) }
                assertEquals(listOf("FIRST", "SECOND"), modes.map { it.value })
                assertEquals(listOf("fixture.Mode", "fixture.Mode"),
                    modes.map { assertIs<ClassType>(it.type).fullyQualifiedName })
                val nestedArray = assertIs<Iterable<*>>(annotation.values.getValue("nestedArray"))
                    .map { assertIs<AnnotationUsage>(it) }
                assertEquals(listOf("fixture.Nested", "fixture.Nested"), nestedArray.map { it.annotation.fullyQualifiedName })
                assertEquals(listOf(setOf("value"), setOf("value")), nestedArray.map { it.values.keys })
                assertEquals(listOf("left", "right"),
                    nestedArray.map { assertIs<SootStringConstant>(it.values.getValue("value")).value })
            }
        }
    }

    @Test
    fun `fresh wrappers share source signature and keep body caches independent`() {
        withClasses { optimized, stock ->
            val source = assertNotNull(optimized.classSource.streamingMethodSources()).single { it.name == "caller" }
            val first = source.asStreamingMethod(optimized.type)
            val second = source.asStreamingMethod(optimized.type)
            assertNotSame(first, second)
            assertSame(first.signature, second.signature)
            assertSame(source.getSignature(), first.signature)
            val firstBody = first.body
            assertSame(firstBody, first.body, "each wrapper keeps its own normal SootUp memoization")
            val secondBody = second.body
            assertNotSame(firstBody, secondBody, "another wrapper must resolve a fresh body")
            assertSame(secondBody, second.body)
            val expected = stock.methods.single { it.name == "caller" }.body
            assertBodyEquals(expected, firstBody)
            assertBodyEquals(expected, secondBody)
            assertTrue(firstBody.controlFlowGraph.stmts.any {
                it.toString().contains("staticinvoke <fixture.Streamed: int target(int)>(7)")
            })
            assertTrue(firstBody.controlFlowGraph.stmts.any { it.toString().startsWith("return ") })
        }
    }

    @Test
    fun `absent local variable table preserves body names flow and lines without an instruction index`() {
        withClasses(scopedClassBytes(withLocals = false, withLocalAnnotations = false)) { optimized, stock ->
            val source = assertNotNull(optimized.classSource.streamingMethodSources()).single()
            val expected = stock.methods.single()
            val original = assertIs<AsmMethodSource>(expected.bodySource)
            assertEquals(emptyList(), original.localVariables)
            assertNull(source.localVariables)
            repeat(3) {
                // Match source resolution counts: SootUp retains line state between bodies.
                // Its own copy method preserves stock metadata/source with a fresh body cache.
                val expectedBody = expected.withSource(expected.bodySource).body
                val method = source.asStreamingMethod(optimized.type)
                val body = method.body
                assertEquals(expected.annotations.toList(), method.annotations.toList())
                assertBodyEquals(expectedBody, body)
                assertScopedBody(body, namedLocals = false, localAnnotations = false)
                assertTrue(assertIs<Map<*, *>>(scratch(original, "insnIndexCache")).isNotEmpty())
                assertNull(scratch(source, "insnIndexCache"), "empty debug data must not require instruction positions")
                assertEquals(emptyMap<Any?, Any?>(), scratch(source, "localVarTypeAnnotationIndex"))
                releaseScratch(source)
                assertNull(source.localVariables)
                assertBodyEquals(expectedBody, body)
            }
        }
    }

    @Test
    fun `local type annotations without a variable table retain their scopes and instruction index`() {
        withClasses(scopedClassBytes(withLocals = false)) { optimized, stock ->
            val source = assertNotNull(optimized.classSource.streamingMethodSources()).single()
            val expected = stock.methods.single()
            assertNull(source.localVariables)
            val visible = assertNotNull(source.visibleLocalVariableAnnotations)
            val invisible = assertNotNull(source.invisibleLocalVariableAnnotations)
            assertEquals(listOf("Lfixture/First;"), visible.map { it.desc })
            assertEquals(listOf("Lfixture/Second;"), invisible.map { it.desc })
            repeat(3) {
                // Match source resolution counts: SootUp retains line state between bodies.
                // Its own copy method preserves stock metadata/source with a fresh body cache.
                val expectedBody = expected.withSource(expected.bodySource).body
                val method = source.asStreamingMethod(optimized.type)
                val body = method.body
                assertEquals(expected.annotations.toList(), method.annotations.toList())
                assertBodyEquals(expectedBody, body)
                assertScopedBody(body, namedLocals = false)
                assertTrue(assertIs<Map<*, *>>(scratch(source, "insnIndexCache")).isNotEmpty())
                assertTrue(assertIs<Map<*, *>>(scratch(source, "localVarTypeAnnotationIndex")).isNotEmpty())
                releaseScratch(source)
                assertNull(scratch(source, "insnIndexCache"))
                assertNull(scratch(source, "localVarTypeAnnotationIndex"))
                assertSame(visible, source.visibleLocalVariableAnnotations)
                assertSame(invisible, source.invisibleLocalVariableAnnotations)
                assertNull(source.localVariables)
                assertBodyEquals(expectedBody, body)
            }
        }
    }

    @Test
    fun `adapter cleanup preserves scoped annotations and exception flow when a source is reused`() {
        withClasses(scopedClassBytes()) { optimized, stock ->
            val source = assertNotNull(optimized.classSource.streamingMethodSources()).single()
            val expected = stock.methods.single()
            val variableTable = assertNotNull(source.localVariables)
            assertEquals(listOf("choose", "second", "first", "caught"), variableTable.map { it.name })
            assertEquals(listOf(0, 1, 1, 2), variableTable.map { it.index })
            val adapter = SootUpAdapter(
                JavaView(emptyList()),
                LoaderConfig(buildCallGraph = false, trackCrossMethodFunctionalDispatch = false),
                extensions = emptyList(),
                inputLocationSources = emptyMap(),
                singleArtifactSource = "fixture.jar"
            )
            val release = SootUpAdapter::class.java.getDeclaredMethod("releaseConversionState", Any::class.java)
                .apply { isAccessible = true }
            repeat(3) {
                // Match source resolution counts: SootUp retains line state between bodies.
                // Its own copy method preserves stock metadata/source with a fresh body cache.
                val expectedBody = expected.withSource(expected.bodySource).body
                val method = source.asStreamingMethod(optimized.type)
                val body = method.body
                assertEquals(expected.annotations.toList(), method.annotations.toList())
                assertAnnotationValues(method.annotations, listOf("fixture.Result" to "result"))
                assertBodyEquals(expectedBody, body)
                assertScopedBody(body)
                assertSame(variableTable, source.localVariables)
                assertTrue(assertIs<Map<*, *>>(scratch(source, "insnIndexCache")).isNotEmpty())
                assertTrue(assertIs<Map<*, *>>(scratch(source, "localVarTypeAnnotationIndex")).isNotEmpty())
                release.invoke(adapter, source)
                assertNull(scratch(source, "insnIndexCache"))
                assertNull(scratch(source, "localVarTypeAnnotationIndex"))
                assertBodyEquals(expectedBody, body)
                assertScopedBody(body)
                assertSame(variableTable, source.localVariables)
            }
        }
    }

    @Test
    fun `source enumeration sorts overloads by descriptor on every pass`() {
        withClasses { optimized, _ ->
            val expected = listOf(
                "<init>()V", "abstractValue()I", "caller()I", "choose()Ljava/lang/String;",
                "choose(I[Ljava/lang/String;)Ljava/lang/String;", "generic(Ljava/util/List;)Ljava/lang/Object;", "target(I)I"
            )
            repeat(3) {
                val sources = assertNotNull(optimized.classSource.streamingMethodSources())
                assertEquals(expected, sources.map { it.name + it.desc })
                assertEquals(expected.size, sources.map { it.asStreamingMethod(optimized.type).signature }.toSet().size)
            }
        }
    }

    @Test
    fun `adapter and declared signatures do not force the persistent class method set`() {
        withClasses { optimized, _ ->
            var eagerReads = 0
            val guarded = object : JavaSootClass(optimized.classSource, SourceType.Application) {
                override fun getMethods(): Set<JavaSootMethod> {
                    eagerReads++
                    error("persistent method set must not be resolved")
                }
            }
            val view = object : JavaView(emptyList()) {
                override fun getClasses(): Stream<JavaSootClass> = Stream.of(guarded)
                override fun getClass(type: ClassType): Optional<JavaSootClass> =
                    if (type == guarded.type) Optional.of(guarded) else Optional.empty()
            }
            val adapter = SootUpAdapter(
                view,
                LoaderConfig(buildCallGraph = false, trackCrossMethodFunctionalDispatch = false),
                extensions = emptyList(),
                inputLocationSources = emptyMap(),
                singleArtifactSource = "fixture.jar"
            )
            val factory = view.identifierFactory
            val declares = SootUpAdapter::class.java.getDeclaredMethod(
                "declaresMethod", ClassType::class.java, MethodSubSignature::class.java
            ).apply { isAccessible = true }
            val intType = factory.getType("int")
            assertEquals(true, declares.invoke(
                adapter, guarded.type, factory.getMethodSubSignature("target", intType, listOf(intType))
            ))
            assertEquals(false, declares.invoke(
                adapter, guarded.type, factory.getMethodSubSignature("target", intType, emptyList())
            ))
            assertEquals(0, eagerReads)

            val graph = adapter.buildGraph()
            assertEquals(setOf("<init>", "abstractValue", "caller", "choose", "generic", "target"),
                graph.methods(MethodPattern()).map { it.name }.toSet())
            val call = graph.nodes(CallSiteNode::class.java).single { it.caller.name == "caller" }
            assertEquals("fixture.Streamed", call.callee.declaringClass.className)
            assertEquals("target", call.callee.name)
            assertEquals(7, assertIs<IntConstant>(graph.node(call.arguments.single())).value)
            assertEquals(0, eagerReads, "neither graph construction nor the subsignature index may load every wrapper")
        }
    }

    private fun assertBodyEquals(expected: Body, actual: Body) {
        assertEquals(expected.controlFlowGraph.stmts.map { it.toString() }, actual.controlFlowGraph.stmts.map { it.toString() })
        fun locals(body: Body) = body.locals.filterIsInstance<JavaLocal>().associate { local ->
            local.name to (local.type.toString() to local.annotations.map { it.toString() })
        }
        assertEquals(locals(expected), locals(actual))
        assertEquals(topology(expected), topology(actual))
        assertEquals(expected.controlFlowGraph.stmts.map { it.positionInfo.stmtPosition },
            actual.controlFlowGraph.stmts.map { it.positionInfo.stmtPosition })
    }

    private fun topology(body: Body): List<Pair<List<Int>, Map<String, Int>>> {
        val graph = body.controlFlowGraph
        val statements = graph.stmts
        return statements.map { statement ->
            graph.successors(statement).map { successor -> statements.indexOfFirst { it === successor } } to
                graph.exceptionalSuccessors(statement).map { (type, target) ->
                    type.fullyQualifiedName to statements.indexOfFirst { it === target }
                }.toMap()
        }
    }

    private fun scratch(source: AsmMethodSource, name: String): Any? =
        AsmMethodSource::class.java.getDeclaredField(name).apply { isAccessible = true }.get(source)

    private fun releaseScratch(source: AsmMethodSource) {
        val adapter = SootUpAdapter(
            JavaView(emptyList()), LoaderConfig(buildCallGraph = false),
            extensions = emptyList(), inputLocationSources = emptyMap(), singleArtifactSource = "fixture.jar"
        )
        SootUpAdapter::class.java.getDeclaredMethod("releaseConversionState", Any::class.java).apply {
            isAccessible = true
        }.invoke(adapter, source)
    }

    private fun assertAnnotationValues(actual: Iterable<AnnotationUsage>, expected: List<Pair<String, String>>) {
        assertEquals(expected.map { it.first }, actual.map { it.annotation.fullyQualifiedName })
        assertEquals(expected.map { setOf("value") }, actual.map { it.values.keys })
        assertEquals(expected.map { it.second }, actual.map { assertIs<SootStringConstant>(it.values.getValue("value")).value })
    }

    private fun assertScopedBody(body: Body, namedLocals: Boolean = true, localAnnotations: Boolean = true) {
        val parameterName = if (namedLocals) "choose" else "l0"
        val localName = if (namedLocals) "first" else "l1"
        val locals = body.locals.filterIsInstance<JavaLocal>().associateBy { it.name }
        val parameter = locals.getValue(parameterName)
        assertEquals("int", parameter.type.toString())
        assertAnnotationValues(parameter.annotations,
            listOf("fixture.Parameter" to "declaration", "fixture.ParameterType" to "type"))
        val local = locals.getValue(localName)
        assertEquals("int", local.type.toString())
        assertAnnotationValues(local.annotations, if (localAnnotations) listOf("fixture.First" to "first-scope") else emptyList())
        assertFalse("second" in locals, "one slot retains the local selected in its first active scope")
        val statements = body.controlFlowGraph.stmts
        val rendered = statements.map { it.toString() }
        assertTrue(rendered.containsAll(listOf("$localName = 1", "$localName = 2", "return $localName", "return -1")))
        assertEquals(10, statements.single { it.toString() == "$localName = 1" }.positionInfo.stmtPosition.firstLine)
        assertEquals(30, statements.single { it.toString() == "return -1" }.positionInfo.stmtPosition.firstLine)
        val divisionStatement = statements.filterIsInstance<JAssignStmt>().single { it.rightOp is JDivExpr }
        val division = assertIs<JDivExpr>(divisionStatement.rightOp)
        assertEquals(10, assertIs<SootIntConstant>(division.op1).value)
        assertEquals(parameterName, assertIs<Local>(division.op2).name)
        assertEquals(setOf("java.lang.ArithmeticException"),
            body.controlFlowGraph.exceptionalSuccessors(divisionStatement).keys.map { it.fullyQualifiedName }.toSet())
        val exceptionalEdges = statements.flatMap { body.controlFlowGraph.exceptionalSuccessors(it).entries }
        exceptionalEdges.forEach { (type, target) ->
            assertEquals("java.lang.ArithmeticException", type.fullyQualifiedName)
            assertIs<JCaughtExceptionRef>(assertIs<JIdentityStmt>(target).rightOp)
        }
    }

    private fun withClasses(bytes: ByteArray = classBytes(), action: (JavaSootClass, JavaSootClass) -> Unit) {
        val root = Files.createTempDirectory("streaming-class-methods")
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

    private fun classBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "fixture/Streamed", null, "java/lang/Object", null)
        writeAnnotatedMethod(writer)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "target", "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 0)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "caller", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/Streamed", "target", "(I)I", false)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "abstractValue", "()I", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "choose", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("empty")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "generic", "(Ljava/util/List;)Ljava/lang/Object;",
            "<T:Ljava/lang/Object;>(Ljava/util/List<TT;>;)TT;", null).apply {
            writeRichAnnotation(this)
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun scopedClassBytes(withLocals: Boolean = true, withLocalAnnotations: Boolean = true): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Streamed", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "scoped", "(I)I", null, null)
        method.visitParameterAnnotation(0, "Lfixture/Parameter;", true).apply {
            visit("value", "declaration")
            visitEnd()
        }
        val parameterRef = TypeReference.newFormalParameterReference(0).value
        method.visitTypeAnnotation(parameterRef, null, "Lfixture/ParameterType;", false).apply {
            visit("value", "type")
            visitEnd()
        }
        val returnRef = TypeReference.newTypeReference(TypeReference.METHOD_RETURN).value
        method.visitTypeAnnotation(returnRef, null, "Lfixture/Result;", true).apply {
            visit("value", "result")
            visitEnd()
        }
        val start = Label()
        val second = Label()
        val tryEnd = Label()
        val handler = Label()
        val end = Label()
        method.visitTryCatchBlock(start, tryEnd, handler, "java/lang/ArithmeticException")
        method.visitCode()
        method.visitLabel(start)
        method.visitLineNumber(10, start)
        method.visitInsn(Opcodes.ICONST_1)
        method.visitVarInsn(Opcodes.ISTORE, 1)
        method.visitVarInsn(Opcodes.ILOAD, 0)
        method.visitJumpInsn(Opcodes.IFEQ, second)
        method.visitIntInsn(Opcodes.BIPUSH, 10)
        method.visitVarInsn(Opcodes.ILOAD, 0)
        method.visitInsn(Opcodes.IDIV)
        method.visitVarInsn(Opcodes.ISTORE, 1)
        method.visitVarInsn(Opcodes.ILOAD, 1)
        method.visitInsn(Opcodes.IRETURN)
        method.visitLabel(second)
        method.visitLineNumber(20, second)
        method.visitInsn(Opcodes.ICONST_2)
        method.visitVarInsn(Opcodes.ISTORE, 1)
        method.visitLabel(tryEnd)
        method.visitVarInsn(Opcodes.ILOAD, 1)
        method.visitInsn(Opcodes.IRETURN)
        method.visitLabel(handler)
        method.visitLineNumber(30, handler)
        method.visitVarInsn(Opcodes.ASTORE, 2)
        method.visitInsn(Opcodes.ICONST_M1)
        method.visitInsn(Opcodes.IRETURN)
        method.visitLabel(end)
        if (withLocals) {
            method.visitLocalVariable("choose", "I", null, start, end, 0)
            method.visitLocalVariable("second", "I", null, second, tryEnd, 1)
            method.visitLocalVariable("first", "I", null, start, second, 1)
            method.visitLocalVariable("caught", "Ljava/lang/ArithmeticException;", null, handler, end, 2)
        }
        if (withLocalAnnotations) {
            val localRef = TypeReference.newTypeReference(TypeReference.LOCAL_VARIABLE).value
            method.visitLocalVariableAnnotation(localRef, null, arrayOf(start), arrayOf(second), intArrayOf(1),
                "Lfixture/First;", true).apply {
                visit("value", "first-scope")
                visitEnd()
            }
            method.visitLocalVariableAnnotation(localRef, null, arrayOf(second), arrayOf(tryEnd), intArrayOf(1),
                "Lfixture/Second;", false).apply {
                visit("value", "second-scope")
                visitEnd()
            }
        }
        method.visitMaxs(2, 3)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun writeRichAnnotation(method: MethodVisitor) {
        method.visitAnnotation("Lfixture/Rich;", true).apply {
            visitEnum("mode", "Lfixture/Mode;", "FIRST")
            visitAnnotation("nested", "Lfixture/Nested;").apply {
                visit("value", "inside")
                visitEnd()
            }
            visitArray("labels").apply {
                visit(null, "alpha")
                visit(null, "beta")
                visitEnd()
            }
            visitArray("modes").apply {
                visitEnum(null, "Lfixture/Mode;", "FIRST")
                visitEnum(null, "Lfixture/Mode;", "SECOND")
                visitEnd()
            }
            visitArray("nestedArray").apply {
                listOf("left", "right").forEach { value ->
                    val nested = visitAnnotation(null, "Lfixture/Nested;")
                    nested.visit("value", value)
                    nested.visitEnd()
                }
                visitEnd()
            }
            visitEnd()
        }
    }

    private fun writeAnnotatedMethod(writer: ClassWriter) {
        val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_VARARGS
        val method = writer.visitMethod(access, "choose", "(I[Ljava/lang/String;)Ljava/lang/String;", null,
            arrayOf("java/io/IOException", "java/lang/ReflectiveOperationException"))
        method.visitAnnotation("Lfixture/Declared;", true).apply {
            visit("value", "declaration")
            visitEnd()
        }
        method.visitAnnotation("Lfixture/HiddenDeclared;", false).apply {
            visit("value", "hidden-declaration")
            visitEnd()
        }
        val returnType = TypeReference.newTypeReference(TypeReference.METHOD_RETURN).value
        method.visitTypeAnnotation(returnType, null, "Lfixture/Returned;", true).apply {
            visit("value", "return")
            visit("rank", 7)
            visitEnd()
        }
        method.visitTypeAnnotation(returnType, null, "Lfixture/HiddenReturned;", false).apply {
            visit("value", "hidden-return")
            visitEnd()
        }
        method.visitCode()
        method.visitLdcInsn("chosen")
        method.visitInsn(Opcodes.ARETURN)
        method.visitMaxs(1, 2)
        method.visitEnd()
    }
}
