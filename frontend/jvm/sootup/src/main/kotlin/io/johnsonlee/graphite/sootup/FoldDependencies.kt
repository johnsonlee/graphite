package io.johnsonlee.graphite.sootup

import sootup.core.jimple.common.Local
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.Stmt

/** Calls whose operands originate in a rule replacement, including copies and erased-value casts. */
internal class FoldDependencies(statements: Collection<Stmt>, roots: Set<Local>) {
    private val dependent: MutableSet<Stmt>

    init {
        val locals = roots.toMutableSet()
        do {
            var changed = false
            for (stmt in statements.filterIsInstance<JAssignStmt>()) {
                val source = (stmt.rightOp as? JCastExpr)?.op ?: stmt.rightOp
                if (source in locals) (stmt.leftOp as? Local)?.let { changed = locals.add(it) || changed }
            }
        } while (changed)
        dependent = statements.filterTo(HashSet()) { stmt -> stmt.uses.any { it in locals } }
    }

    operator fun contains(stmt: Stmt): Boolean = stmt in dependent

    /** Constant propagation creates new statements; retain the origin after a local becomes a literal. */
    fun replace(old: Stmt, replacement: Stmt) {
        if (dependent.remove(old)) dependent.add(replacement)
    }
}
