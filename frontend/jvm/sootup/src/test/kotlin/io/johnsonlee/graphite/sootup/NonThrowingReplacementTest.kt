package io.johnsonlee.graphite.sootup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.graph.MutableBlockControlFlowGraph
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.types.ClassType
import sootup.java.core.JavaIdentifierFactory

/**
 * Pins the SootUp 3.0.1 graph bugs that require [replaceWithNonThrowingStmt]. The first two
 * tests should fail after upstream fixes statement-local exception updates; then reassess
 * whether the insertion/removal workaround can be replaced by the upstream API.
 */
class NonThrowingReplacementTest {

    private class Fixture {
        val graph = MutableBlockControlFlowGraph()
        val first = JNopStmt(StmtPositionInfo.getNoStmtPositionInfo())
        val neighbor = JNopStmt(StmtPositionInfo.getNoStmtPositionInfo())
        val exit = JReturnVoidStmt(StmtPositionInfo.getNoStmtPositionInfo())
        val handler = JReturnVoidStmt(StmtPositionInfo.getNoStmtPositionInfo())
        val exception = JavaIdentifierFactory.getInstance().getClassType("java.lang.RuntimeException")
        val handlers: Map<ClassType, Stmt> = mapOf(exception to handler)

        init {
            graph.addBlock(listOf(first, neighbor, exit), handlers)
            graph.setStartingStmt(first)
            assertEquals(listOf(first, neighbor, exit), graph.getBlockOf(first)!!.stmts)
            assertSame(graph.getBlockOf(first), graph.getBlockOf(neighbor))
            assertEquals(handlers, graph.exceptionalSuccessors(first))
            assertEquals(handlers, graph.exceptionalSuccessors(neighbor))
        }
    }

    @Test
    fun `clearExceptionalEdges also clears the handlers of block mates`() {
        val fixture = Fixture()
        fixture.graph.clearExceptionalEdges(fixture.first)

        assertEquals(emptyMap(), fixture.graph.exceptionalSuccessors(fixture.first))
        assertEquals(emptyMap(), fixture.graph.exceptionalSuccessors(fixture.neighbor))
        assertEquals(emptyMap(), fixture.graph.exceptionalSuccessors(fixture.exit))
    }

    @Test
    fun `addNode cannot update exceptions at the head of a block ending in return`() {
        val fixture = Fixture()
        val failure = assertFailsWith<IndexOutOfBoundsException> {
            fixture.graph.addNode(fixture.first, emptyMap())
        }

        assertEquals("successorIdx '0' is out of bounds ('0 for return')", failure.message)
    }

    @Test
    fun `replacement preserves neighbor handlers entry and normal flow`() {
        val fixture = Fixture()
        val replacement = JNopStmt(StmtPositionInfo.getNoStmtPositionInfo())

        replaceWithNonThrowingStmt(fixture.graph, fixture.first, replacement)

        assertSame(replacement, fixture.graph.startingStmt)
        assertEquals(emptyMap(), fixture.graph.exceptionalSuccessors(replacement))
        assertEquals(fixture.handlers, fixture.graph.exceptionalSuccessors(fixture.neighbor))
        assertEquals(listOf(fixture.neighbor), fixture.graph.successors(replacement))
        assertEquals(listOf(fixture.exit), fixture.graph.successors(fixture.neighbor))
        assertTrue(fixture.first !in fixture.graph.nodes)
        assertTrue(fixture.graph.blocks.all { it.stmts.isNotEmpty() })
    }
}
