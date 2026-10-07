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
 *
 * Both issues remain in upstream develop at ad0fe7b7 (MutableBlockControlFlowGraph):
 * https://github.com/soot-oss/SootUp/blob/ad0fe7b75926a860b0612c2c79ffa12fd068741b/sootup.core/src/main/java/sootup/core/graph/MutableBlockControlFlowGraph.java
 * NonThrowingReplacementTest pins the upstream failures. Revisit this workaround when a
 * SootUp upgrade makes those tests fail because statement-local exception updates are fixed.
 */
internal fun replaceWithNonThrowingStmt(graph: MutableControlFlowGraph, old: Stmt, replacement: Stmt) {
    graph.insertBefore(old, listOf(replacement as FallsThroughStmt), emptyMap())
    graph.removeNode(old, true)
}
