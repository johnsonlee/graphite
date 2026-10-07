package io.johnsonlee.graphite.sootup

import sootup.core.graph.MutableControlFlowGraph
import sootup.core.jimple.common.stmt.FallsThroughStmt
import sootup.core.jimple.common.stmt.Stmt

/**
 * Replace one call by a non-throwing statement without changing its neighbors' handlers.
 * SootUp 3.0.1 clears exceptional edges for the whole block, and updating an existing node's
 * exception map can create an empty block. Inserting a new statement with its own exception
 * map splits only at nonempty boundaries; removing the old statement preserves normal flow.
 * insertBefore also updates the entry when [old] was the first statement.
 */
internal fun replaceWithNonThrowingStmt(graph: MutableControlFlowGraph, old: Stmt, replacement: Stmt) {
    graph.insertBefore(old, listOf(replacement as FallsThroughStmt), emptyMap())
    graph.removeNode(old, true)
}
