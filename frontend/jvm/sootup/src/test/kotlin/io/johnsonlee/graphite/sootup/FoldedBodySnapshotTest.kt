package io.johnsonlee.graphite.sootup

import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.graph.ImmutableBlockControlFlowGraph
import sootup.core.graph.MutableBasicBlock
import sootup.core.graph.MutableBlockControlFlowGraph
import sootup.core.jimple.basic.FullStmtPositionInfo
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.Immediate
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JNewMultiArrayExpr
import sootup.core.jimple.common.expr.JPhiExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JGotoStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt
import sootup.core.model.Body
import sootup.core.model.FullPosition
import sootup.core.model.Position
import sootup.core.model.SourceType
import sootup.core.types.ArrayType
import sootup.java.core.AnnotationUsage
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.jimple.basic.JavaLocal
import sootup.java.core.views.JavaView

class FoldedBodySnapshotTest {
    private val factory = JavaIdentifierFactory.getInstance()
    private val position = StmtPositionInfo.getNoStmtPositionInfo()

    @Test
    fun `snapshot preserves typed branches exceptions and dynamic invocations after source release`() {
        val root = Files.createTempDirectory("folded-body-snapshot")
        val snapshot: FoldedBodySnapshot
        val expected: List<Any>
        try {
            val source = root.resolve("SnapshotFixture.java")
            Files.writeString(source, """
                public class SnapshotFixture {
                    public static int value(int key) {
                        try {
                            switch (key) {
                                case 1: return Integer.parseInt("" + key);
                                case 7: return new int[key][2].length;
                                default: return -3;
                            }
                        } catch (NumberFormatException failure) { return -5; }
                    }
                }
            """.trimIndent())
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", root.toString(), source.toString()))
            val view = JavaView(ParsedClassLocation(root, SourceType.Application, emptyList()))
            val method = view.getClass(factory.getClassType("SnapshotFixture")).orElseThrow().methods.single { it.name == "value" }
            val builder = Body.builder(method.body, method.modifiers)
            val original = builder.build()
            expected = facts(original)
            snapshot = FoldedBodySnapshot.capture(original)
            assertEquals(expected, facts(snapshot.body))
            val statements = snapshot.body.stmts
            assertEquals(listOf(1, 7), statements.filterIsInstance<JSwitchStmt>().single().values.map { it.value })
            assertTrue(statements.flatMap { it.uses }.any { it is JDynamicInvokeExpr })
            assertTrue(statements.flatMap { it.uses }.any { it is JNewMultiArrayExpr })
            assertTrue(statements.any { snapshot.body.controlFlowGraph.exceptionalSuccessors(it).isNotEmpty() })
            assertNotSame(original.stmts.first(), snapshot.body.stmts.first())
            builder.controlFlowGraph.blocks.toList().forEach(builder.controlFlowGraph::removeBlock)
            builder.locals.clear()
            assertEquals(expected, facts(snapshot.body), "the snapshot must not forward to its original builder")
        } finally {
            root.toFile().deleteRecursively()
        }
        assertEquals(expected, facts(snapshot.body), "no class source or archive is needed to read the retained body")
        assertIs<ImmutableBlockControlFlowGraph>(snapshot.body.controlFlowGraph)
        assertTrue(snapshot.body.controlFlowGraph.blocks.none { it is MutableBasicBlock })
    }

    @Test
    fun `snapshot owns sizes switch labels positions and nested local annotations`() {
        val labels = mutableListOf(IntConstant.getInstance(1), IntConstant.getInstance(7))
        val sizes = mutableListOf<Immediate>(IntConstant.getInstance(3), IntConstant.getInstance(4))
        val annotationElements = mutableListOf<Any>(IntConstant.getInstance(8))
        val annotationValues = mutableMapOf<String, Any>("values" to annotationElements)
        val annotations = mutableListOf(AnnotationUsage(factory.getClassType("sample.Marker"), annotationValues))
        val input = JavaLocal("input", factory.getType("int"), annotations)
        val array = Local("array", ArrayType(factory.getType("int"), 2))
        val positions = arrayOf<Position?>(
            FullPosition(4, 1, 4, 2), null, null, FullPosition(5, 1, 5, 9)
        )
        val fullPosition = FullStmtPositionInfo(FullPosition(3, 0, 3, 20), positions)
        val assign = JAssignStmt(array, JNewMultiArrayExpr(array.type as ArrayType, sizes), fullPosition)
        val call = JInvokeStmt(JStaticInvokeExpr(
            factory.getMethodSignature("sample.Use", "accept", "void", listOf("int")), listOf(input)
        ), position)
        val switch = JSwitchStmt(input, labels, position)
        val first = JReturnVoidStmt(position)
        val second = JReturnVoidStmt(position)
        val default = JReturnVoidStmt(position)
        val graph = MutableBlockControlFlowGraph()
        graph.addBlock(listOf(assign, call, switch))
        graph.addNode(first)
        graph.addNode(second)
        graph.addNode(default)
        graph.setStartingStmt(assign)
        graph.setEdges(switch, listOf(first, second, default))
        val original = body(graph, linkedSetOf(input, array))
        val snapshot = FoldedBodySnapshot.capture(original)
        sizes[0] = IntConstant.getInstance(99)
        labels[0] = IntConstant.getInstance(99)
        annotationElements[0] = IntConstant.getInstance(99)
        annotationValues["extra"] = IntConstant.getInstance(99)
        annotations.clear()
        positions[3] = FullPosition(99, 1, 99, 2)

        val copiedAssign = snapshot.body.stmts.filterIsInstance<JAssignStmt>().single()
        val copiedSizes = assertIs<JNewMultiArrayExpr>(copiedAssign.rightOp).sizes
        assertEquals(listOf(3, 4), copiedSizes.map { assertIs<IntConstant>(it).value })
        assertEquals(listOf(1, 7), snapshot.body.stmts.filterIsInstance<JSwitchStmt>().single().values.map { it.value })
        assertNull(copiedAssign.positionInfo.getOperandPosition(1))
        assertEquals(5, copiedAssign.positionInfo.getOperandPosition(3).firstLine)
        val copiedLocal = assertIs<JavaLocal>(snapshot.body.locals.single { it.name == "input" })
        val annotation = copiedLocal.annotations.single()
        assertEquals(setOf("values"), annotation.values.keys)
        assertEquals(listOf(IntConstant.getInstance(8)), annotation.values["values"])
        val copiedInvoke = snapshot.body.stmts.filterIsInstance<JInvokeStmt>().single().invokeExpr.orElseThrow()
        assertSame(copiedLocal, copiedInvoke.args.single(), "instructions and local metadata must share the owned local")
        assertFailsWith<UnsupportedOperationException> { (copiedSizes as MutableList)[0] = IntConstant.getInstance(10) }
        assertFailsWith<UnsupportedOperationException> { (copiedInvoke.args as MutableList)[0] = input }
        assertFailsWith<UnsupportedOperationException> { (copiedLocal.annotations as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (annotation.values["values"] as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (annotation.values as MutableMap).clear() }
    }

    @Test
    fun `all graph mutation entrances reject mutation or affect only computed copies`() {
        val handler = JReturnVoidStmt(position)
        val exit = JReturnVoidStmt(position)
        val exception = factory.getClassType("java.lang.RuntimeException")
        val graph = MutableBlockControlFlowGraph()
        graph.addBlock(listOf(exit), mapOf(exception to handler))
        graph.setStartingStmt(exit)
        val snapshot = FoldedBodySnapshot.capture(body(graph))
        val frozen = snapshot.body.controlFlowGraph
        val expected = facts(snapshot.body)
        assertFailsWith<UnsupportedOperationException> { (snapshot.body.locals as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (frozen.nodes as MutableCollection).clear() }
        assertFailsWith<UnsupportedOperationException> { (frozen.blocks as MutableCollection).clear() }
        assertFailsWith<UnsupportedOperationException> { (frozen.stmts as MutableList).clear() }
        for (block in frozen.blocks) {
            assertFailsWith<UnsupportedOperationException> { (block.stmts as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (block.successors as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (block.predecessors as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (block.exceptionalSuccessors as MutableMap).clear() }
            (block.exceptionalPredecessors as MutableMap).clear() // SootUp returns a fresh map.
        }
        val copiedExit = frozen.startingStmt
        (frozen.exceptionalSuccessors(copiedExit) as MutableMap).clear() // Also a fresh map.
        assertFailsWith<UnsupportedOperationException> { frozen.removeExceptionalFlowFromAllBlocks(exception, frozen.stmts.last()) }
        assertEquals(expected, facts(snapshot.body))
    }

    @Test
    fun `null operand position array is copied without inventing positions`() {
        val graph = MutableBlockControlFlowGraph()
        val exit = JReturnVoidStmt(FullStmtPositionInfo(FullPosition(3, 0, 3, 10), null))
        graph.addNode(exit)
        graph.setStartingStmt(exit)
        val snapshot = FoldedBodySnapshot.capture(body(graph))
        val copied = snapshot.body.stmts.single().positionInfo
        assertEquals(3, copied.stmtPosition.firstLine)
        val field = FullStmtPositionInfo::class.java.getDeclaredField("operandPositions")
        field.isAccessible = true
        assertNull(field.get(copied))
    }

    @Test
    fun `snapshot preserves every instruction and edge in a disconnected dead loop`() {
        val entry = JReturnVoidStmt(position)
        val unreachableLocal = Local("unreachable", factory.getType("int"))
        val unreachableAssign = JAssignStmt(unreachableLocal, IntConstant.getInstance(41), position)
        val unreachableBackEdge = JGotoStmt(position)
        val graph = MutableBlockControlFlowGraph()
        graph.addNode(entry)
        graph.addBlock(listOf(unreachableAssign, unreachableBackEdge))
        graph.putEdge(unreachableBackEdge, 0, unreachableAssign)
        graph.setStartingStmt(entry)
        val original = body(graph, setOf(unreachableLocal))
        val snapshot = FoldedBodySnapshot.capture(original)
        for (candidate in listOf(original, snapshot.body)) {
            val candidateGraph = candidate.controlFlowGraph
            val nodes = candidateGraph.nodes
            assertEquals(3, nodes.size, "all source nodes, including the dead loop, must survive")
            assertEquals(2, candidateGraph.blocks.size)
            val assign = nodes.filterIsInstance<JAssignStmt>().single()
            val backEdge = nodes.filterIsInstance<JGotoStmt>().single()
            val returned = nodes.filterIsInstance<JReturnVoidStmt>().single()
            assertEquals("unreachable", assertIs<Local>(assign.leftOp).name)
            assertEquals(41, assertIs<IntConstant>(assign.rightOp).value)
            assertEquals(listOf(backEdge), candidateGraph.successors(assign))
            assertEquals(listOf(assign), candidateGraph.successors(backEdge))
            assertTrue(candidateGraph.successors(returned).isEmpty())
            assertSame(returned, candidateGraph.startingStmt)
            assertTrue(nodes.all { candidateGraph.exceptionalSuccessors(it).isEmpty() })
            assertEquals(listOf("unreachable"), candidate.locals.map { it.name })
        }
        assertEquals(facts(original), facts(snapshot.body))
    }

    @Test
    fun `table switches retain their representation and ordered targets`() {
        val key = Local("key", factory.getType("int"))
        val switch = JSwitchStmt(key, 2, 3, position)
        val exits = List(3) { JReturnVoidStmt(position) }
        val graph = MutableBlockControlFlowGraph()
        graph.addNode(switch)
        exits.forEach(graph::addNode)
        graph.setEdges(switch, exits)
        graph.setStartingStmt(switch)
        val snapshot = FoldedBodySnapshot.capture(body(graph, setOf(key)))
        val copied = snapshot.body.stmts.filterIsInstance<JSwitchStmt>().single()
        assertTrue(copied.isTableSwitch)
        assertEquals(listOf(2, 3), copied.values.map { it.value })
        assertEquals(3, snapshot.body.controlFlowGraph.successors(copied).size)
        assertEquals(facts(body(graph, setOf(key))), facts(snapshot.body))
    }

    @Test
    fun `SSA phi fails capture instead of retaining mutable predecessor blocks`() {
        val left = Local("left", factory.getType("int"))
        val right = Local("right", factory.getType("int"))
        val graph = MutableBlockControlFlowGraph()
        val exit = JReturnVoidStmt(position)
        graph.addNode(exit)
        graph.setStartingStmt(exit)
        val phi = JPhiExpr(listOf(left, right), mapOf(left to graph.getBlockOf(exit), right to graph.getBlockOf(exit)))
        val assign = JAssignStmt(left, phi, position)
        graph.insertBefore(exit, assign)
        val failure = assertFailsWith<IllegalStateException> { FoldedBodySnapshot.capture(body(graph, setOf(left, right))) }
        assertTrue(failure.message.orEmpty().contains("JPhiExpr"))
        assertFalse(failure.message.orEmpty().contains("complete"))
    }

    private fun body(graph: MutableBlockControlFlowGraph, locals: Set<Local> = emptySet()): Body =
        Body.builder(graph)
            .setMethodSignature(factory.getMethodSignature("sample.Snapshot", "value", "void", emptyList()))
            .setLocals(LinkedHashSet(locals))
            .build()

    private fun facts(body: Body): List<Any> {
        val graph = body.controlFlowGraph
        val statements = body.stmts
        return listOf(
            body.methodSignature.toString(), body.position.toString(),
            body.locals.map { it.name to it.type.toString() }.sortedBy { it.first },
            statements.map { it.javaClass.name to it.toString() },
            statements.map { stmt -> graph.successors(stmt).map(statements::indexOf) },
            statements.map { stmt -> graph.exceptionalSuccessors(stmt).entries.map { (type, target) ->
                type.toString() to statements.indexOf(target)
            }.sortedBy { it.first } }
        )
    }
}
