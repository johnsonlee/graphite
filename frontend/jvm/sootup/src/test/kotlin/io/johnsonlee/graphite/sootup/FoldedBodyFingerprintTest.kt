package io.johnsonlee.graphite.sootup

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import sootup.core.graph.MutableBlockControlFlowGraph
import sootup.core.jimple.basic.FullStmtPositionInfo
import sootup.core.jimple.basic.SimpleStmtPositionInfo
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.Immediate
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.constant.ClassConstant
import sootup.core.jimple.common.constant.DoubleConstant
import sootup.core.jimple.common.constant.EnumConstant
import sootup.core.jimple.common.constant.FloatConstant
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.LongConstant
import sootup.core.jimple.common.constant.MethodHandle
import sootup.core.jimple.common.constant.MethodType
import sootup.core.jimple.common.constant.NullConstant
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JNewMultiArrayExpr
import sootup.core.jimple.common.ref.JInstanceFieldRef
import sootup.core.jimple.common.ref.JThisRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JGotoStmt
import sootup.core.jimple.common.stmt.JIdentityStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.jimple.javabytecode.stmt.JBreakpointStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.Body
import sootup.core.model.FullPosition
import sootup.core.model.Position
import sootup.core.types.ArrayType
import sootup.java.core.AnnotationUsage
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.jimple.basic.JavaLocal

class FoldedBodyFingerprintTest {
    private val factory = JavaIdentifierFactory.getInstance()
    private val position = StmtPositionInfo.getNoStmtPositionInfo()
    private val owner = factory.getClassType("sample.Fingerprint")
    private val intType = factory.getType("int")

    @Test
    fun `payload free statements keep their instruction identity and control flow`() {
        fun snapshot(prefix: Stmt?): FoldedBodySnapshot {
            val exit = JReturnVoidStmt(position)
            val graph = MutableBlockControlFlowGraph()
            if (prefix == null) {
                graph.addBlock(listOf(exit))
            } else if (prefix is JGotoStmt) {
                graph.addBlock(listOf(prefix))
                graph.addBlock(listOf(exit))
                graph.putEdge(prefix, 0, exit)
            } else {
                graph.addBlock(listOf(prefix, exit))
            }
            graph.setStartingStmt(prefix ?: exit)
            graph.validateStmtConnectionsInGraph()
            return capture(graph, returns = "void")
        }
        val prefixes = listOf(null, JGotoStmt(position), JNopStmt(position), JBreakpointStmt(position))
        val snapshots = prefixes.map(::snapshot)
        snapshots.zip(prefixes).forEach { (retained, prefix) ->
            val graph = retained.body.controlFlowGraph
            val exit = graph.nodes.filterIsInstance<JReturnVoidStmt>().single()
            assertEquals(prefix?.javaClass ?: JReturnVoidStmt::class.java, graph.startingStmt.javaClass)
            assertEquals(if (prefix == null) emptyList() else listOf(exit), graph.successors(graph.startingStmt))
        }
        assertEquals(snapshots.size, snapshots.map { it.semanticFingerprint() }.toSet().size)
        assertEquals(snapshots.map { it.semanticFingerprint() }, prefixes.map { snapshot(it).semanticFingerprint() })
    }

    @Test
    fun `scalar annotation fingerprints preserve both runtime type and value`() {
        val pairs = listOf(
            "1" to "2", true to false, 1.toByte() to 2.toByte(), 1.toShort() to 2.toShort(),
            1 to 2, 1L to 2L, '1' to '2'
        )
        val snapshots = pairs.map { (first, second) ->
            val retained = metadata(operands(3), listOf(first))
            val local = assertIs<JavaLocal>(retained.body.locals.single())
            val values = assertIs<List<*>>(local.annotations.single().values["entries"])
            assertEquals(first, values.single())
            assertDifferent(retained, metadata(operands(3), listOf(second)))
            retained
        }
        assertEquals(snapshots.size, snapshots.map { it.semanticFingerprint() }.toSet().size,
            "equal textual representations in different scalar types must remain distinct")
    }

    @Test
    fun `constant fingerprints preserve wide integers and floating point NaN payloads`() {
        val longs = listOf(1L, 0x100000001L)
        val longSnapshots = longs.map { literal(LongConstant.getInstance(it), "long") }
        assertEquals(longs, longSnapshots.map { assertIs<LongConstant>(returned(it)).value })
        assertDifferent(longSnapshots[0], longSnapshots[1])

        val floatBits = listOf(0x7fc00001, 0x7fc00002)
        val floatSnapshots = floatBits.map { literal(FloatConstant.getInstance(Float.fromBits(it)), "float") }
        assertEquals(floatBits, floatSnapshots.map { assertIs<FloatConstant>(returned(it)).value.toRawBits() })
        assertDifferent(floatSnapshots[0], floatSnapshots[1])

        val doubleBits = listOf(0x7ff8000000000001L, 0x7ff8000000000002L)
        val doubleSnapshots = doubleBits.map { literal(DoubleConstant.getInstance(Double.fromBits(it)), "double") }
        assertEquals(doubleBits, doubleSnapshots.map { assertIs<DoubleConstant>(returned(it)).value.toRawBits() })
        assertDifferent(doubleSnapshots[0], doubleSnapshots[1])
    }

    @Test
    fun `class enum and null reference constants keep their typed identities`() {
        val classType = factory.getClassType("java.lang.Class")
        val firstClass = literal(ClassConstant("Lsample/First;", classType), "java.lang.Object")
        val secondClass = literal(ClassConstant("Lsample/Second;", classType), "java.lang.Object")
        assertEquals("Lsample/First;", assertIs<ClassConstant>(returned(firstClass)).value)
        assertDifferent(firstClass, secondClass)

        val enumType = factory.getClassType("sample.Flag")
        val firstEnum = literal(EnumConstant("ON", enumType), "java.lang.Object")
        val secondEnum = literal(EnumConstant("OFF", enumType), "java.lang.Object")
        val retained = assertIs<EnumConstant>(returned(firstEnum))
        assertEquals("ON", retained.value)
        assertEquals(enumType, retained.type)
        assertDifferent(firstEnum, secondEnum)

        val nullReference = literal(NullConstant.getInstance(), "java.lang.Object")
        assertIs<NullConstant>(returned(nullReference))
        assertDifferent(firstEnum, nullReference)
    }

    @Test
    fun `bootstrap argument handles and method types retain signatures and reference kinds`() {
        val original = bootstrap(MethodHandle.Kind.REF_INVOKE_STATIC, "int", "java.lang.String")
        val invocation = assertIs<JDynamicInvokeExpr>(
            original.body.stmts.filterIsInstance<JInvokeStmt>().single().invokeExpr.orElseThrow()
        )
        val handle = assertIs<MethodHandle>(invocation.bootstrapArgs[0])
        assertEquals(MethodHandle.Kind.REF_INVOKE_STATIC, handle.kind)
        assertEquals(owner, handle.referenceSignature.declClassType)
        assertEquals("target", handle.referenceSignature.name)
        val methodType = assertIs<MethodType>(invocation.bootstrapArgs[1])
        assertEquals(listOf(intType), methodType.parameterTypes)
        assertEquals(factory.getType("java.lang.String"), methodType.returnType)
        assertFailsWith<UnsupportedOperationException> { (invocation.bootstrapArgs as MutableList).clear() }

        assertDifferent(original, bootstrap(MethodHandle.Kind.REF_INVOKE_VIRTUAL, "int", "java.lang.String"))
        assertDifferent(original, bootstrap(MethodHandle.Kind.REF_INVOKE_STATIC, "long", "java.lang.String"))
        assertDifferent(original, bootstrap(MethodHandle.Kind.REF_INVOKE_STATIC, "int", "java.lang.Object"))
    }

    @Test
    fun `multi array sizes and instance field identity affect the fingerprint independently`() {
        val original = arrayAndField(3, "count")
        val assignments = original.body.stmts.filterIsInstance<JAssignStmt>()
        val allocation = assertIs<JNewMultiArrayExpr>(assignments[0].rightOp)
        assertEquals(listOf(2, 3), allocation.sizes.map { assertIs<IntConstant>(it).value })
        val field = assertIs<JInstanceFieldRef>(assignments[1].rightOp)
        assertEquals("count", field.fieldSignature.name)
        assertEquals(owner, field.fieldSignature.declClassType)
        assertSame(original.body.getThisLocal(), field.base)
        assertDifferent(original, arrayAndField(4, "count"))
        assertDifferent(original, arrayAndField(3, "otherCount"))
    }

    @Test
    fun `operand positions and nested annotation payloads survive source mutation and affect the fingerprint`() {
        val operandPositions = operands(3)
        val annotationValues = annotationPayload()
        val retained = metadata(operandPositions, annotationValues)
        val before = retained.semanticFingerprint()
        assertDifferent(retained, metadata(operands(4), annotationPayload()))
        assertDifferent(retained, metadata(operands(3), annotationPayload(floatBits = 0x7fc00002)))
        assertDifferent(retained, metadata(operands(3), annotationPayload(doubleBits = 0x7ff8000000000002L)))

        operandPositions[1] = FullPosition(9, 9, 9, 10)
        annotationValues.clear()
        assertEquals(before, retained.semanticFingerprint())
        val assignment = retained.body.stmts.filterIsInstance<JAssignStmt>().single()
        assertEquals(3, assertNotNull(assignment.positionInfo.getOperandPosition(1)).firstCol)
        val local = assertIs<JavaLocal>(retained.body.locals.single())
        val values = assertIs<List<*>>(local.annotations.single().values["entries"])
        assertEquals("label", values[0])
        assertEquals(42, assertIs<IntConstant>(values[1]).value)
        val nested = assertIs<AnnotationUsage>(values[2])
        assertEquals(0x7fc00001, assertIs<Float>(nested.values["ratio"]).toRawBits())
        assertEquals(0x7ff8000000000001L, assertIs<Double>(nested.values["weight"]).toRawBits())
        assertFailsWith<UnsupportedOperationException> { (values as MutableList<*>).clear() }
    }

    @Test
    fun `unknown statement and position subclasses cannot silently lose backend metadata`() {
        val branch = object : JGotoStmt(position) {}
        val exit = JReturnStmt(IntConstant.getInstance(7), position)
        val graph = MutableBlockControlFlowGraph()
        graph.addNode(branch)
        graph.addNode(exit)
        graph.putEdge(branch, 0, exit)
        graph.setStartingStmt(branch)
        assertCaptureRejected("Unsupported folded-body statement") { capture(graph) }

        val unknownPosition = object : SimpleStmtPositionInfo(7) {}
        val returned = JReturnStmt(IntConstant.getInstance(7), unknownPosition)
        assertCaptureRejected("Unsupported folded-body position information") { capture(listOf(returned)) }
    }

    @org.junit.Test(timeout = 10_000)
    fun `disconnected blocks remain fingerprinted independently of insertion order`() {
        fun snapshot(value: Int, deadFirst: Boolean): FoldedBodySnapshot {
            val graph = MutableBlockControlFlowGraph()
            val entry = JReturnStmt(IntConstant.getInstance(7), position)
            val local = Local("unreachable", intType)
            val assign = JAssignStmt(local, IntConstant.getInstance(value), position)
            val back = JGotoStmt(position)
            if (!deadFirst) graph.addBlock(listOf(entry))
            graph.addBlock(listOf(assign, back))
            graph.putEdge(back, 0, assign)
            if (deadFirst) graph.addBlock(listOf(entry))
            graph.setStartingStmt(entry)
            // Only the goto jumps backwards: a self-fallthrough if is not a linearizable Jimple body.
            graph.validateStmtConnectionsInGraph()
            assertTrue(assign.fallsThrough())
            assertFalse(back.fallsThrough())
            assertEquals(listOf(back), graph.successors(assign))
            assertEquals(listOf(assign), graph.successors(back))
            val sourceOrder = graph.stmts.map { it.javaClass }
            return capture(graph, setOf(local)).also { snapshot ->
                snapshot.body.controlFlowGraph.validateStmtConnectionsInGraph()
                assertEquals(sourceOrder, snapshot.body.stmts.map { it.javaClass },
                    "capture must preserve the source instruction order, including the unreachable block")
            }
        }
        val original = snapshot(11, false)
        val reordered = snapshot(11, true)
        val graph = original.body.controlFlowGraph
        val assign = graph.nodes.filterIsInstance<JAssignStmt>().single()
        val back = graph.nodes.filterIsInstance<JGotoStmt>().single()
        assertEquals(7, assertIs<IntConstant>(graph.nodes.filterIsInstance<JReturnStmt>().single().op).value)
        assertEquals(11, assertIs<IntConstant>(assign.rightOp).value, "the unreachable assignment remains in the immutable body")
        assertEquals("unreachable", assertIs<Local>(assign.leftOp).name)
        assertEquals(listOf(back), graph.successors(assign))
        assertEquals(listOf(assign), graph.successors(back))
        assertEquals(original.semanticFingerprint(), reordered.semanticFingerprint(),
            "backend block insertion order must not alter the identity of a disconnected loop")
        assertDifferent(original, snapshot(12, false))
    }

    @Test
    fun `unrecognized local metadata fails capture before any mutable value can escape`() {
        val unknownLocal = object : Local("custom", intType) {}
        val returned = JReturnStmt(IntConstant.getInstance(7), position)
        assertCaptureRejected("Unsupported folded-body local") { capture(listOf(returned), setOf(unknownLocal)) }

        val unknownAnnotation = AnnotationUsage(factory.getClassType("sample.Marker"),
            mapOf("unmodeled" to arrayOf("a mutable backend value")))
        val annotated = JavaLocal("annotated", intType, listOf(unknownAnnotation))
        assertCaptureRejected("Unsupported folded-body annotation value") { capture(listOf(returned), setOf(annotated)) }
    }

    private fun bootstrap(kind: MethodHandle.Kind, parameter: String, returns: String): FoldedBodySnapshot {
        val target = factory.getMethodSignature("sample.Fingerprint", "target", "java.lang.String", listOf("int"))
        val handle = MethodHandle(target, kind, factory.getClassType("java.lang.invoke.MethodHandle"))
        val descriptor = factory.getMethodSignature("sample.Fingerprint", "descriptor", returns, listOf(parameter))
        val methodType = MethodType(descriptor.subSignature, factory.getClassType("java.lang.invoke.MethodType"))
        val bootstrap = factory.getMethodSignature("sample.Bootstrap", "bootstrap", "java.lang.invoke.CallSite", emptyList())
        val invoked = factory.getMethodSignature("sootup.dummy.InvokeDynamic", "read", "int", emptyList())
        val call = JInvokeStmt(JDynamicInvokeExpr(bootstrap, listOf(handle, methodType), invoked, 6, emptyList()), position)
        return capture(listOf(call, JReturnStmt(IntConstant.getInstance(7), position)))
    }

    private fun arrayAndField(columns: Int, fieldName: String): FoldedBodySnapshot {
        val receiver = Local("receiver", owner)
        val arrayType = ArrayType(intType, 2)
        val array = Local("array", arrayType)
        val result = Local("result", intType)
        val field = factory.getFieldSignature(fieldName, owner, intType)
        val sizes = listOf(IntConstant.getInstance(2), IntConstant.getInstance(columns))
        return capture(listOf(
            JIdentityStmt(receiver, JThisRef(owner), position),
            JAssignStmt(array, JNewMultiArrayExpr(arrayType, sizes), position),
            JAssignStmt(result, JInstanceFieldRef(receiver, field), position),
            JReturnStmt(result, position)
        ), setOf(receiver, array, result))
    }

    private fun operands(column: Int): Array<Position?> = arrayOf(null, FullPosition(5, column, 5, column + 1))

    private fun annotationPayload(
        floatBits: Int = 0x7fc00001, doubleBits: Long = 0x7ff8000000000001L
    ): MutableList<Any> = mutableListOf("label", IntConstant.getInstance(42), AnnotationUsage(
        factory.getClassType("sample.Nested"), mapOf("ratio" to Float.fromBits(floatBits), "weight" to Double.fromBits(doubleBits))
    ))

    private fun metadata(operands: Array<Position?>, values: List<Any>): FoldedBodySnapshot {
        val annotation = AnnotationUsage(factory.getClassType("sample.Marker"), mapOf("entries" to values))
        val local = JavaLocal("annotated", intType, listOf(annotation))
        val fullPosition = FullStmtPositionInfo(FullPosition(5, 0, 5, 10), operands)
        return capture(listOf(JAssignStmt(local, IntConstant.getInstance(7), fullPosition), JReturnStmt(local, position)), setOf(local))
    }

    private fun returned(snapshot: FoldedBodySnapshot): Immediate =
        assertIs<JReturnStmt>(snapshot.body.stmts.single()).op

    private fun literal(value: Immediate, returns: String): FoldedBodySnapshot =
        capture(listOf(JReturnStmt(value, position)), returns = returns)

    private fun assertDifferent(first: FoldedBodySnapshot, second: FoldedBodySnapshot) {
        assertNotEquals(first.semanticFingerprint(), second.semanticFingerprint())
    }

    private fun assertCaptureRejected(reason: String, capture: () -> FoldedBodySnapshot) {
        val failure = assertFailsWith<IllegalStateException> { capture() }
        assertContains(failure.message.orEmpty(), reason)
    }

    private fun capture(
        statements: List<Stmt>, locals: Set<Local> = emptySet(), returns: String = "int"
    ): FoldedBodySnapshot {
        val graph = MutableBlockControlFlowGraph()
        graph.addBlock(statements)
        graph.setStartingStmt(statements.first())
        return capture(graph, locals, returns)
    }

    private fun capture(
        graph: MutableBlockControlFlowGraph, locals: Set<Local> = emptySet(), returns: String = "int"
    ): FoldedBodySnapshot = FoldedBodySnapshot.capture(Body.builder(graph)
        .setMethodSignature(factory.getMethodSignature("sample.Fingerprint", "read", returns, emptyList()))
        .setLocals(LinkedHashSet(locals))
        .build())
}
